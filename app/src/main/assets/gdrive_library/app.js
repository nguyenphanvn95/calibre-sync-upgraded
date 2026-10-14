
// ── STATE ──────────────────────────────────────────────────────
// So với bản gốc (đọc thẳng metadata_public.json từ Google Drive qua Apps
// Script proxy, hỗ trợ nhiều thư viện + hộp thoại chọn thư viện + modal xem
// chi tiết sách): bản này chỉ phục vụ ĐÚNG 1 thư viện mỗi phiên WebView (do
// GDriveLibraryWebViewActivity quyết định trước khi nạp trang), lấy dữ liệu
// same-origin từ chính server cục bộ ("/api/library.json", xem
// GDriveOpdsHttpServer#handleLibraryJson) thay vì gọi thẳng Google Drive, và
// xem chi tiết/tải sách qua "AndroidBridge" (window.AndroidBridge, xem
// GDriveLibraryJsBridge) thay vì modal HTML.
let allBooks = [];
let fileFormats = {};      // bookId → [{format, name}]
let filtered = [];
let currentPage = 1;
const PAGE_SIZE = 20;
let currentFilter = 'all';
let currentTag = null;
let currentFormat = null;
let currentLang = null;
let currentPublisher = null;
let currentSort = 'added_desc';
let comments = {};

// True sau khi /api/library.json đã nạp thành công lần đầu -- dùng để biết có
// nên thử tải ảnh bìa/gọi AndroidBridge hay chưa (tương tự "folderMode" ở bản gốc).
let libraryLoaded = false;

// bookId (number) → raw book entry từ /api/library.json (book_id, formats{}, cover_url,...).
let booksById = {};

// ── INIT ──────────────────────────────────────────────────────
window.addEventListener('DOMContentLoaded', () => {
  loadLibrary();
});

// Tải "/api/library.json" từ chính server cục bộ đang phục vụ trang này
// (same-origin, không CORS/proxy gì cả) và build thư viện sách từ đó.
async function loadLibrary() {
  document.getElementById('autoloadZone').style.display = 'flex';
  document.getElementById('autoloadCard').innerHTML = `
    <div class="autoload-icon">📚</div>
    <h2>Đang tải thư viện…</h2>
    <p>Đang đọc dữ liệu đã đồng bộ trên máy.</p>`;
  document.getElementById('appZone').style.display = 'none';

  try {
    const res = await fetch('/api/library.json');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    showAppZoneLoading();
    loadPublicJsonData(data);
  } catch (err) {
    document.getElementById('autoloadCard').innerHTML = `
      <div class="autoload-icon">⚠️</div>
      <h2>Không tải được thư viện</h2>
      <p>Không đọc được dữ liệu đã đồng bộ trên máy.</p>
      <p style="font-size:12px">${esc(err.message || '')}</p>
      <button class="btn-folder retry" onclick="loadLibrary()">🔄 Thử lại</button>`;
  }
}

// Chuyển từ màn hình "đang tải" sang khung app chính, hiện spinner xử lý dữ liệu.
function showAppZoneLoading() {
  document.getElementById('autoloadZone').style.display = 'none';
  document.getElementById('appZone').style.display = 'flex';
  document.getElementById('loadingMain').style.display = 'flex';
  document.getElementById('loadingMain').innerHTML = `<div class="spinner"></div><span>Đang xử lý dữ liệu…</span>`;
  document.getElementById('bookGrid').innerHTML = '';
  document.getElementById('pagination').innerHTML = '';
}

// Shared "we now have allBooks[] populated, wire up the rest of the UI" step.
function finalizeBooksLoaded() {
  buildSidebar();
  document.getElementById('searchWrap').style.display = 'flex';
  document.getElementById('viewMenuWrap').style.display = 'flex';
  chooseViewOption(currentViewMode);
  document.getElementById('totalCount').style.display = 'inline';
  document.getElementById('totalCount').textContent = `${allBooks.length} cuốn`;
  document.getElementById('badgeAll').textContent = allBooks.length;
  document.getElementById('badgeCover').textContent = allBooks.filter(b => b.has_cover).length;
  document.getElementById('badgeRated').textContent = allBooks.filter(b => b.rating).length;

  if (!finalizeBooksLoaded._searchWired) {
    finalizeBooksLoaded._searchWired = true;
    document.getElementById('searchInput').addEventListener('input', debounce(() => {
      currentPage = 1; applyFilters();
    }, 220));
  }

  document.getElementById('loadingMain').style.display = 'none';
  currentPage = 1;
  applyFilters();
}

// Parse dữ liệu đã tải từ "/api/library.json" và build allBooks[]/fileFormats{}.
// Chấp nhận cả 2 dạng: { "books": { "23": {...} } } (dạng chính hiện tại, xem
// GDriveOpdsHttpServer#handleLibraryJson) và dạng phẳng { "23": {...} } (phòng
// khi cấu trúc đổi sau này) để phần parse dưới đây không phải sửa lại.
function loadPublicJsonData(data) {
  comments = {};
  fileFormats = {};
  booksById = {};
  libraryLoaded = true;

  const books = data.books || data;
  allBooks = Object.keys(books).map(key => {
    const entry = books[key];
    const id = entry.book_id != null ? entry.book_id : parseInt(key, 10);
    booksById[id] = entry;

    const formatKeys = Object.keys(entry.formats || {});
    fileFormats[id] = formatKeys.map(fmt => ({ format: fmt, name: fmt }));

    const authorsStr = entry.authors || '';
    const tagsArr = Array.isArray(entry.tags) ? entry.tags.filter(Boolean)
      : (entry.tags ? String(entry.tags).split(',').map(s => s.trim()).filter(Boolean) : []);
    const langsArr = Array.isArray(entry.languages) ? entry.languages.filter(Boolean)
      : (entry.languages ? [entry.languages] : []);
    // GDriveLibraryDb (cache cục bộ trên máy) không có cột pubdate/date_added riêng
    // -- last_modified là trường thời gian duy nhất có sẵn, dùng làm proxy cho thứ
    // tự "mới nhập trước/sau"; nếu thiếu, sắp theo book_id (id lớn hơn = nhập sau,
    // đúng với cách Calibre cấp id tăng dần).
    const added = entry.last_modified || '';

    if (entry.comments) comments[id] = entry.comments;

    return {
      id,
      title: entry.title || '(Không có tiêu đề)',
      author_sort: entry.author_sort || authorsStr,
      authors: authorsStr,
      authors_arr: authorsStr ? authorsStr.split(',').map(s => s.trim()).filter(Boolean) : [],
      tags: tagsArr.join(','),
      tags_arr: tagsArr,
      added,
      has_cover: !!entry.has_cover,
      cover_url: entry.cover_url || null,
      series_index: entry.series_index != null ? entry.series_index : null,
      publisher: entry.publisher || '',
      series: entry.series || '',
      formats: formatKeys.join(','),
      formats_arr: formatKeys,
      rating: normalizeCalibreRating(entry.rating),
      lang: normalizeLangCode(langsArr[0] || ''),
      inDevice: !!entry.in_device_library,
      // Chuỗi tìm kiếm đã bỏ dấu tiếng Việt + viết thường, tính sẵn 1 lần lúc
      // nạp thư viện để tìm kiếm không dấu (xem hàm normalizeSearchText) nhanh,
      // không phải tính lại mỗi lần gõ phím.
      search_blob: normalizeSearchText([
        entry.title || '', authorsStr, tagsArr.join(' '), entry.publisher || '', entry.series || ''
      ].join(' '))
    };
  });

  if (!allBooks.length) { showEmpty('Thư viện chưa có sách nào.'); return; }

  finalizeBooksLoaded();
}

// Calibre's ratings table stores values on a 0-10 scale (2 per star); tolerate
// a plain 0-5 star value too, in case a given export uses that scale instead.
function normalizeCalibreRating(r) {
  if (r == null || r === '') return 0;
  const n = parseFloat(r);
  if (isNaN(n)) return 0;
  return n <= 5 ? n * 2 : n;
}

// ── ẢNH BÌA / TẢI FILE: qua server cục bộ (GDriveOpdsHttpServer), không còn
// gọi thẳng Google Drive từ WebView nữa -- server đã proxy sẵn 2 route này. ──
function getCoverUrls(bookId, hasCover) {
  if (!hasCover || !libraryLoaded) return [];
  const entry = booksById[bookId];
  return entry && entry.cover_url ? [entry.cover_url] : [];
}

function loadImgWithFallback(img, candidates, idx = 0) {
  if (idx >= candidates.length) return;
  img.onerror = () => loadImgWithFallback(img, candidates, idx + 1);
  img.src = candidates[idx];
}

// ── SIDEBAR ──────────────────────────────────────────────────
function buildSidebar() {
  const fmtMap = {};
  allBooks.forEach(b => b.formats_arr.forEach(f => { fmtMap[f] = (fmtMap[f]||0)+1; }));
  document.getElementById('formatList').innerHTML = Object.entries(fmtMap).sort((a,b)=>b[1]-a[1]).map(([f,c]) =>
    `<button class="sidebar-item" onclick="filterByFormat('${f}',this)">
      ${fmtIcon(f)} ${f} <span class="badge">${c}</span>
    </button>`).join('');

  const langMap = {};
  allBooks.forEach(b => { if (b.lang) langMap[b.lang] = (langMap[b.lang]||0)+1; });
  document.getElementById('langList').innerHTML = Object.entries(langMap).sort((a,b)=>b[1]-a[1]).map(([l,c]) =>
    `<button class="sidebar-item" onclick="filterByLang('${l}',this)">
      ${langFlag(l)} ${langName(l)} <span class="badge">${c}</span>
    </button>`).join('');

  const tagMap = {};
  allBooks.forEach(b => b.tags_arr.forEach(t => { if(t) tagMap[t] = (tagMap[t]||0)+1; }));
  document.getElementById('tagList').innerHTML = Object.entries(tagMap).sort((a,b)=>b[1]-a[1]).map(([t,c]) =>
    `<button class="sidebar-item" onclick="filterByTag('${escAttr(t)}',this)">
      🏷 ${esc(t)} <span class="badge">${c}</span>
    </button>`).join('');

  const pubMap = {};
  allBooks.forEach(b => { if (b.publisher) pubMap[b.publisher] = (pubMap[b.publisher]||0)+1; });
  document.getElementById('publisherList').innerHTML = Object.entries(pubMap).sort((a,b)=>b[1]-a[1]).map(([p,c]) =>
    `<button class="sidebar-item" onclick="filterByPublisher('${escAttr(p)}',this)">
      🏢 ${esc(p)} <span class="badge">${c}</span>
    </button>`).join('');
}

// ── SIDEBAR DRAWER (chỉ có hiệu lực trên màn hình hẹp, xem @media 700px trong
// index.html) ─────────────────────────────────────────────────────────────
// Trên desktop, #sidebar luôn hiển thị tĩnh (position: sticky) nên các hàm này
// chỉ thật sự tạo hiệu ứng trượt/đóng-mở khi window.innerWidth <= 700; ở màn
// hình rộng, thêm/bỏ class 'sidebar-open' trên <body> không ảnh hưởng gì vì
// CSS tương ứng nằm trong @media (max-width: 700px).
function toggleSidebar() {
  const isOpen = document.body.classList.toggle('sidebar-open');
  document.getElementById('sidebarToggle').classList.toggle('active', isOpen);
  document.body.style.overflow = isOpen ? 'hidden' : '';
}
function closeSidebar() {
  document.body.classList.remove('sidebar-open');
  document.getElementById('sidebarToggle').classList.remove('active');
  document.body.style.overflow = '';
}
// Đóng drawer khi người dùng chọn 1 bộ lọc trên điện thoại, để họ thấy ngay
// kết quả lọc thay vì phải tự bấm ra ngoài/bấm lại icon hamburger.
function closeSidebarIfMobile() {
  if (window.innerWidth <= 700) closeSidebar();
}
// Nếu người dùng xoay ngang/thay đổi kích thước cửa sổ từ hẹp sang rộng hơn
// 700px trong lúc drawer đang mở, dọn lại trạng thái để không bị kẹt
// 'sidebar-open'/overflow:hidden khi quay lại giao diện desktop tĩnh.
window.addEventListener('resize', debounce(() => {
  if (window.innerWidth > 700) closeSidebar();
}, 150));
// Đóng drawer bằng phím Escape (repo này không có modal HTML/closeModalDirect()
// như bản tham khảo, nên chỉ xử lý đóng sidebar).
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeSidebar(); });

// ── FILTERS ──────────────────────────────────────────────────
function clearSidebarActive() {
  document.querySelectorAll('#sidebar .sidebar-item').forEach(el => el.classList.remove('active'));
}
function filterBy(type, el) {
  clearSidebarActive(); el.classList.add('active');
  currentFilter = type; currentTag = null; currentFormat = null; currentLang = null; currentPublisher = null;
  currentPage = 1; applyFilters();
  closeSidebarIfMobile();
}
function filterByFormat(fmt, el) {
  clearSidebarActive(); el.classList.add('active');
  currentFilter = 'format'; currentFormat = fmt;
  currentTag = null; currentLang = null; currentPublisher = null;
  currentPage = 1; applyFilters();
  closeSidebarIfMobile();
}
function filterByLang(lang, el) {
  clearSidebarActive(); el.classList.add('active');
  currentFilter = 'lang'; currentLang = lang;
  currentTag = null; currentFormat = null; currentPublisher = null;
  currentPage = 1; applyFilters();
  closeSidebarIfMobile();
}
function filterByTag(tag, el) {
  clearSidebarActive(); el.classList.add('active');
  currentFilter = 'tag'; currentTag = tag;
  currentFormat = null; currentLang = null; currentPublisher = null;
  currentPage = 1; applyFilters();
  closeSidebarIfMobile();
}
function filterByPublisher(pub, el) {
  clearSidebarActive(); el.classList.add('active');
  currentFilter = 'publisher'; currentPublisher = pub;
  currentTag = null; currentFormat = null; currentLang = null;
  currentPage = 1; applyFilters();
  closeSidebarIfMobile();
}
function applySort() {
  currentSort = document.getElementById('sortSelect').value;
  currentPage = 1; applyFilters();
}

function applyFilters() {
  const q = document.getElementById('searchInput').value.toLowerCase().trim();
  filtered = allBooks.filter(b => {
    if (currentFilter === 'has_cover' && !b.has_cover) return false;
    if (currentFilter === 'rated' && !b.rating) return false;
    if (currentFilter === 'format' && !b.formats_arr.includes(currentFormat)) return false;
    if (currentFilter === 'lang' && b.lang !== currentLang) return false;
    if (currentFilter === 'tag' && !b.tags_arr.includes(currentTag)) return false;
    if (currentFilter === 'publisher' && b.publisher !== currentPublisher) return false;
    if (q) {
      // Tìm không dấu: cả từ khoá lẫn dữ liệu sách đều được bỏ dấu trước khi
      // so khớp, nên gõ "nguyen nhat anh" vẫn tìm ra "Nguyễn Nhật Ánh".
      return b.search_blob.includes(normalizeSearchText(q));
    }
    return true;
  });

  filtered.sort((a, b) => {
    switch (currentSort) {
      case 'title_asc':   return (a.title||'').localeCompare(b.title||'', 'vi');
      case 'title_desc':  return (b.title||'').localeCompare(a.title||'', 'vi');
      case 'author_asc':  return (a.author_sort||'').localeCompare(b.author_sort||'', 'vi');
      case 'rating_desc': return (b.rating||0) - (a.rating||0);
      case 'added_desc': {
        if (a.added && b.added) return String(b.added).localeCompare(String(a.added));
        if (a.added && !b.added) return -1;
        if (!a.added && b.added) return 1;
        return (b.id||0) - (a.id||0);
      }
      case 'added_asc': {
        if (a.added && b.added) return String(a.added).localeCompare(String(b.added));
        if (a.added && !b.added) return 1;
        if (!a.added && b.added) return -1;
        return (a.id||0) - (b.id||0);
      }
    }
    return 0;
  });

  const count = filtered.length;
  document.getElementById('resultCount').innerHTML =
    `Hiển thị <strong>${Math.min(PAGE_SIZE*(currentPage), count)}</strong> / <strong>${count}</strong> cuốn`;
  renderPage();
  renderPagination();
}

// ── RENDER ────────────────────────────────────────────────────
function renderPage() {
  openSwipeRowEl = null; // DOM sắp bị innerHTML thay hết, tham chiếu cũ không còn hợp lệ.
  const start = (currentPage - 1) * PAGE_SIZE;
  const page = filtered.slice(start, start + PAGE_SIZE);
  const grid = document.getElementById('bookGrid');

  if (!page.length) {
    grid.innerHTML = `<div class="empty" style="grid-column:1/-1">
      <div class="empty-icon">🔍</div><div>Không tìm thấy sách nào</div></div>`;
    return;
  }

  grid.innerHTML = page.map(b => renderCard(b)).join('');

  if (libraryLoaded) {
    page.forEach(b => { if (b.has_cover) loadCardCover(b); });
  }
}

// Xem chi tiết 1 sách giờ mở BookDetailActivity (native, xem
// openBookDetailNative()) thay vì modal HTML -- nên .book-card không còn nút
// tải định dạng riêng bên trong nữa. Vẫn giữ 1 nút tải nhanh nhỏ (⬇) đè lên
// góc bìa sách để thêm thẳng vào Device Library mà không cần mở màn xem
// trước, dùng đúng route AndroidBridge.downloadBook() (xem Phần 3 của prompt
// nâng cấp) -- bấm nút này KHÔNG mở BookDetailActivity (stopPropagation).
// Icon SVG dung chung cho nut tai nhanh (dl-badge) tren moi the sach, thay cho
// emoji "⬇" cu (render xau/khong deu giua cac may) -- xem requestDownloadBook()/
// onNativeDownloadResult(). Dung stroke=currentColor de an theo mau chu cua nut.
const DL_ICON_DOWNLOAD = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12"/><path d="m7 11 5 5 5-5"/><path d="M4.5 20.5h15"/></svg>`;
const DL_ICON_SPINNER = `<svg class="dl-spinner" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M12 3a9 9 0 1 0 9 9"/></svg>`;
const DL_ICON_CHECK = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12.5l5.5 5.5L20 6.5"/></svg>`;
const SWIPE_ICON_OPEN = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><path d="M15 3h6v6"/><path d="M10 14 21 3"/></svg>`;
const SWIPE_ICON_SHARE = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 13.5 6.8 4"/><path d="m15.4 6.5-6.8 4"/></svg>`;

function renderCard(b) {
  const hue = b.id % 8;
  const rating = b.rating ? '★'.repeat(Math.round(b.rating / 2)) : '';
  const formatBadges = b.formats_arr.map(f =>
    `<span class="fmt-badge ${f.toLowerCase()}">${f}</span>`).join('');
  const primaryFormat = pickPrimaryFormat(b.formats_arr);
  const addedText = fmtDateShort(b.added);

  const cardInner = `
    <div class="cover-wrap">
      <div class="cover-placeholder" data-hue="${hue}" id="cover-${b.id}">
        <div class="cover-placeholder-icon">📖</div>
        <div class="cover-placeholder-title">${esc(b.title)}</div>
      </div>
      <div class="format-badges">${formatBadges}</div>
      ${primaryFormat ? renderDlBadge(b, primaryFormat) : ''}
    </div>
    <div class="card-info">
      <div class="card-title">${esc(b.title)}</div>
      <div class="card-author">${esc(b.authors || b.author_sort || '')}</div>
      ${rating ? `<div class="card-rating">${rating}</div>` : ''}
      <div class="card-meta">
        <span class="meta-author">${esc(b.authors || b.author_sort || '')}</span>
        ${rating ? `<span class="meta-dot">·</span><span class="meta-rating">${rating}</span>` : ''}
        ${b.formats ? `<span class="meta-dot">·</span><span class="meta-format">${esc(b.formats)}</span>` : ''}
        ${addedText ? `<span class="meta-dot">·</span><span class="meta-date">${addedText}</span>` : ''}
      </div>
    </div>`;

  // Chỉ bọc swipe-row + render 2 nút hành động khi đang ở list view -- ở grid, vuốt không có ý
  // nghĩa (mỗi ô quá nhỏ, không có chỗ để lộ 2 nút) nên trả về .book-card trần như cũ.
  if (currentViewMode !== 'list' || !primaryFormat) {
    return `<div class="book-card" onclick="openBookDetailNative(${b.id})">${cardInner}</div>`;
  }

  return `<div class="swipe-row" data-book-id="${b.id}">
    <div class="swipe-actions">
      <button type="button" class="swipe-action swipe-action-open"
        onclick="event.stopPropagation();handleSwipeAction(${b.id},'${primaryFormat}','open')">
        ${SWIPE_ICON_OPEN}<span>Tải &amp; Mở</span>
      </button>
      <button type="button" class="swipe-action swipe-action-share"
        onclick="event.stopPropagation();handleSwipeAction(${b.id},'${primaryFormat}','share')">
        ${SWIPE_ICON_SHARE}<span>Tải &amp; Chia sẻ</span>
      </button>
    </div>
    <div class="book-card" onclick="handleCardTap(${b.id})">${cardInner}</div>
  </div>`;
}

// Tách phần nút tải nhanh trên góc bìa ra 1 hàm riêng vì cần render khác nhau tuỳ sách đã
// có trong Device Library hay chưa.
function renderDlBadge(b, primaryFormat) {
  if (b.inDevice) {
    return `<span class="dl-badge dl-badge-done" title="Đã có trong Device Library">${DL_ICON_CHECK}</span>`;
  }
  return `<button class="dl-badge" id="dlbtn-${b.id}" title="Thêm vào thư viện máy"
    onclick="event.stopPropagation();requestDownloadBook(${b.id},'${primaryFormat}')">${DL_ICON_DOWNLOAD}</button>`;
}

function loadCardCover(b) {
  const candidates = getCoverUrls(b.id, b.has_cover);
  if (!candidates.length) return;
  const el = document.getElementById(`cover-${b.id}`);
  if (!el) return;
  const img = document.createElement('img');
  img.onload = () => { el.replaceWith(img); };
  loadImgWithFallback(img, candidates);
}

// ── PAGINATION ────────────────────────────────────────────────
function renderPagination() {
  const total = Math.ceil(filtered.length / PAGE_SIZE);
  if (total <= 1) { document.getElementById('pagination').innerHTML = ''; return; }
  let html = `<button class="page-btn" onclick="goPage(${currentPage-1})" ${currentPage===1?'disabled':''}>‹</button>`;
  const range = pageRange(currentPage, total);
  let prev = null;
  range.forEach(p => {
    if (prev !== null && p - prev > 1) html += `<span class="page-btn" style="opacity:.4;cursor:default">…</span>`;
    html += `<button class="page-btn ${p===currentPage?'active':''}" onclick="goPage(${p})">${p}</button>`;
    prev = p;
  });
  html += `<button class="page-btn" onclick="goPage(${currentPage+1})" ${currentPage===total?'disabled':''}>›</button>`;
  document.getElementById('pagination').innerHTML = html;
}

function pageRange(cur, total) {
  const delta = 2;
  const range = new Set([1, total]);
  for (let i = Math.max(2, cur-delta); i <= Math.min(total-1, cur+delta); i++) range.add(i);
  return [...range].sort((a,b)=>a-b);
}

function goPage(p) {
  const total = Math.ceil(filtered.length / PAGE_SIZE);
  if (p < 1 || p > total) return;
  currentPage = p; renderPage(); renderPagination();
  document.getElementById('bookGrid').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// ── NATIVE BRIDGE (window.AndroidBridge, xem GDriveLibraryJsBridge) ──────
// Chọn định dạng ưu tiên để hiển thị nút tải nhanh trên mỗi thẻ sách -- cùng
// thứ tự ưu tiên với GDriveOpdsHttpServer#pickPrimaryFormat() phía native, để
// 2 bên nhất quán (không bắt buộc phải giống hệt, chỉ để hợp lý).
const FORMAT_PRIORITY = ['EPUB', 'AZW3', 'MOBI', 'PDF', 'FB2', 'TXT'];
function pickPrimaryFormat(formatsArr) {
  for (const f of FORMAT_PRIORITY) if (formatsArr.includes(f)) return f;
  return formatsArr[0] || null;
}

// Mở màn xem trước native (BookDetailActivity) cho 1 sách -- xem
// GDriveLibraryJsBridge#openBookDetail(). Nếu WebView này được mở ngoài app
// (không có AndroidBridge, ví dụ debug trực tiếp trên Chrome desktop), báo lỗi
// nhẹ nhàng thay vì im lặng không làm gì.
// Kèm theo cả danh sách id sách đang hiển thị TRÊN CÙNG 1 TRANG hiện tại (đúng
// thứ tự sort/filter, dùng "filtered" -- toàn bộ tập đã lọc/sắp xếp, chỉ cắt
// lấy đúng trang đang xem giống renderPage() ở trên) để BookDetailActivity có
// thể vuốt trái/phải sang cuốn kế tiếp/trước đó mà không cần quay lại WebView.
// Giới hạn trong phạm vi 1 trang (PAGE_SIZE=20) thay vì cả "filtered" (có thể
// rất dài với thư viện lớn) để tránh phải dựng OPDSEntry (kèm tải bìa) cho quá
// nhiều sách cùng lúc -- xem javadoc BookDetailActivity#EXTRA_BOOK_LIST_GDRIVE_IDS.
function openBookDetailNative(bookId) {
  if (window.AndroidBridge && typeof window.AndroidBridge.openBookDetail === 'function') {
    const start = (currentPage - 1) * PAGE_SIZE;
    const pageIds = filtered.slice(start, start + PAGE_SIZE).map(b => b.id);
    window.AndroidBridge.openBookDetail(bookId, JSON.stringify(pageIds));
  } else {
    console.warn('AndroidBridge.openBookDetail không khả dụng (không chạy trong WebView của app).');
  }
}

// Nút tải nhanh (⬇) trên mỗi thẻ sách -- gọi AndroidBridge.downloadBook(), rồi
// window.onNativeDownloadResult() (được GDriveLibraryJsBridge gọi ngược lại
// qua evaluateJavascript khi xong) cập nhật lại đúng nút này.
function requestDownloadBook(bookId, format) {
  const btn = document.getElementById(`dlbtn-${bookId}`);
  if (btn) { btn.disabled = true; btn.innerHTML = DL_ICON_SPINNER; }
  if (window.AndroidBridge && typeof window.AndroidBridge.downloadBook === 'function') {
    window.AndroidBridge.downloadBook(bookId, format);
  } else {
    console.warn('AndroidBridge.downloadBook không khả dụng (không chạy trong WebView của app).');
    if (btn) { btn.disabled = false; btn.innerHTML = DL_ICON_DOWNLOAD; }
  }
}

// Gọi bởi GDriveLibraryJsBridge#notifyDownloadResult() qua evaluateJavascript.
window.onNativeDownloadResult = function (bookId, success, errorMessage) {
  if (success) {
    const book = allBooks.find(x => x.id === bookId);
    if (book) book.inDevice = true;
  }
  const btn = document.getElementById(`dlbtn-${bookId}`);
  if (!btn) return;
  if (success) {
    const span = document.createElement('span');
    span.className = 'dl-badge dl-badge-done';
    span.title = 'Đã có trong Device Library';
    span.innerHTML = DL_ICON_CHECK;
    btn.replaceWith(span);
    return;
  }
  btn.disabled = false;
  btn.innerHTML = DL_ICON_DOWNLOAD;
  if (errorMessage) btn.title = errorMessage;
};

// ── SEARCH TOGGLE ────────────────────────────────────────────
// Mac dinh header chi hien icon kinh lup (#searchToggle); bam vao mo rong
// #searchWrap thanh 1 o nhap chiem het chieu rong header (xem CSS
// .search-wrap.expanded) va focus luon vao o nhap. Tu dong thu gon lai khi o
// nhap mat focus va dang rong (khong lam mat noi dung dang go/loc).
function toggleSearch() {
  const wrap = document.getElementById('searchWrap');
  if (wrap.classList.contains('expanded')) {
    closeSearch();
  } else {
    wrap.classList.add('expanded');
    document.getElementById('searchToggle').classList.add('active');
    setTimeout(() => document.getElementById('searchInput').focus(), 50);
  }
}
function closeSearch() {
  const wrap = document.getElementById('searchWrap');
  const input = document.getElementById('searchInput');
  wrap.classList.remove('expanded');
  document.getElementById('searchToggle').classList.remove('active');
  input.blur();
  if (input.value) { input.value = ''; currentPage = 1; applyFilters(); }
}
document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('searchInput').addEventListener('blur', () => {
    const input = document.getElementById('searchInput');
    // Neu con dang go/loc (co noi dung), giu nguyen o mo rong de nguoi dung
    // van thay duoc minh dang tim gi -- chi tu thu gon khi o nhap dang rong.
    if (!input.value) {
      document.getElementById('searchWrap').classList.remove('expanded');
      document.getElementById('searchToggle').classList.remove('active');
    }
  });
});

// ── VIEW ──────────────────────────────────────────────────────
// Thay 2 nut Grid/List + popover so cot rieng le truoc day bang 1 dropdown
// "Hien thi" duy nhat (giong menu Display cua tab Device Library trong anh
// chup nguoi dung gui) -- 4 lua chon: Danh sach / Luoi 2/3/4 cuon-hang, moi
// lan chi 1 muc duoc chon (radio), xem #viewMenu trong index.html.
let currentViewMode = 'grid-2'; // 'list' | 'grid-2' | 'grid-3' | 'grid-4' -- mac dinh Luoi 2 cot

function toggleViewMenu(e) {
  e.stopPropagation();
  document.getElementById('viewMenu').classList.toggle('open');
}
function closeViewMenu() {
  document.getElementById('viewMenu').classList.remove('open');
}
function chooseViewOption(mode) {
  currentViewMode = mode;
  document.body.classList.toggle('list-view', mode === 'list');
  if (mode === 'list') {
    document.body.classList.remove('grid-fixed-cols');
  } else {
    const cols = Number(mode.split('-')[1]);
    document.getElementById('bookGrid').style.setProperty('--grid-cols', cols);
    document.body.classList.add('grid-fixed-cols');
  }
  document.querySelectorAll('.view-menu-item').forEach(el =>
    el.classList.toggle('active', el.dataset.view === mode));
  closeViewMenu();
}
document.addEventListener('click', (e) => {
  const wrap = document.getElementById('viewMenuWrap');
  if (wrap && !wrap.contains(e.target)) closeViewMenu();
});

// ── UTILS ─────────────────────────────────────────────────────
function esc(s) {
  if (!s) return '';
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
function escAttr(s) { return (s||'').replace(/\\/g,'\\\\').replace(/'/g,"\\'"); }
function fmtIcon(f) {
  const icons = {EPUB:'📘',PDF:'📄',MP3:'🎵',M4B:'🎙',AAC:'🎧',MOBI:'📱',AZW3:'📱',OGG:'🎵',FLAC:'🎵',CBZ:'🖼',CBR:'🖼'};
  return icons[f] || '📂';
}
function langFlag(code) {
  const flags = {vie:'🇻🇳',eng:'🇺🇸',zho:'🇨🇳',jpn:'🇯🇵',kor:'🇰🇷',fra:'🇫🇷',deu:'🇩🇪',ita:'🇮🇹',spa:'🇪🇸'};
  return flags[code] || '🌐';
}
function langName(code) {
  const names = {vie:'Tiếng Việt',eng:'Tiếng Anh',zho:'Tiếng Trung',jpn:'Tiếng Nhật',kor:'Tiếng Hàn',fra:'Tiếng Pháp',deu:'Tiếng Đức',ita:'Tiếng Ý',spa:'Tiếng Tây Ban Nha'};
  return names[code] || code;
}
// metadata_public.json thường dùng mã 2 ký tự ISO 639-1 (vi, en, ...) trong khi
// sidebar/flags ở trên dùng mã 3 ký tự quen thuộc của Calibre -- map các mã phổ biến.
function normalizeLangCode(code) {
  if (!code) return code;
  const c = code.trim().toLowerCase();
  const map = {vi:'vie',en:'eng',zh:'zho',ja:'jpn',ko:'kor',fr:'fra',de:'deu',it:'ita',es:'spa'};
  return map[c] || c;
}
// Bỏ dấu tiếng Việt + viết thường, dùng cho tìm kiếm không dấu (gõ "sach nau
// an" vẫn tìm ra "Sách nấu ăn"). NFD tách chữ cái ra khỏi dấu thanh/dấu phụ
// cho hầu hết ký tự có dấu; riêng "đ/Đ" không tách được bằng NFD (nó là 1 chữ
// cái riêng chứ không phải "d" + dấu) nên phải thay thế thủ công.
function normalizeSearchText(s) {
  if (!s) return '';
  return String(s)
    .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/đ/g, 'd').replace(/Đ/g, 'D')
    .toLowerCase();
}
// b.added la chuoi last_modified (xem loadPublicJsonData()), co the rong voi sach cu chua
// co moc thoi gian.
function fmtDateShort(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  return d.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' });
}
function debounce(fn, ms) {
  let t; return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}
function showEmpty(msg) {
  document.getElementById('loadingMain').innerHTML =
    `<div class="empty"><div class="empty-icon">📭</div><div>${msg}</div></div>`;
}

// ── SWIPE-TO-REVEAL (chỉ hoạt động ở list view) ─────────────────────────────
const SWIPE_ACTIONS_WIDTH = 152; // px -- PHẢI khớp width của .swipe-actions trong CSS
let swipeDrag = null;       // { row, startX, startY, dx, dragging, pointerId }
let openSwipeRowEl = null;  // .swipe-row đang lộ 2 nút (nếu có)

function initSwipeGestures() {
  const grid = document.getElementById('bookGrid');
  grid.addEventListener('pointerdown', onSwipePointerDown);
  grid.addEventListener('pointermove', onSwipePointerMove, { passive: false });
  grid.addEventListener('pointerup', onSwipePointerUp);
  grid.addEventListener('pointercancel', onSwipePointerUp);
  // Tap ra ngoài mọi swipe-row (kể cả ngoài bookGrid) thì đóng hàng đang mở.
  document.addEventListener('pointerdown', (e) => {
    if (openSwipeRowEl && !openSwipeRowEl.contains(e.target)) hideSwipeRow(openSwipeRowEl);
  });
}

function onSwipePointerDown(e) {
  if (currentViewMode !== 'list') return;
  if (e.target.closest('.swipe-action')) return; // để onclick của nút tự xử lý
  const row = e.target.closest('.swipe-row');
  if (!row) return;
  swipeDrag = { row, startX: e.clientX, startY: e.clientY, dx: 0, dragging: false, pointerId: e.pointerId };
}

function onSwipePointerMove(e) {
  if (!swipeDrag || swipeDrag.pointerId !== e.pointerId) return;
  const dx = e.clientX - swipeDrag.startX;
  const dy = e.clientY - swipeDrag.startY;
  if (!swipeDrag.dragging) {
    if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return;
    if (Math.abs(dy) > Math.abs(dx)) { swipeDrag = null; return; } // cuộn dọc -- bỏ qua, để trang tự cuộn
    swipeDrag.dragging = true;
    if (openSwipeRowEl && openSwipeRowEl !== swipeDrag.row) hideSwipeRow(openSwipeRowEl);
  }
  e.preventDefault();
  const base = swipeDrag.row.classList.contains('swipe-open') ? -SWIPE_ACTIONS_WIDTH : 0;
  let next = base + dx;
  next = Math.max(-SWIPE_ACTIONS_WIDTH - 24, Math.min(24, next)); // rubber-band nhẹ 2 đầu
  swipeDrag.dx = next;
  swipeDrag.row.querySelector('.book-card').style.transform = `translateX(${next}px)`;
}

function onSwipePointerUp(e) {
  if (!swipeDrag || swipeDrag.pointerId !== e.pointerId) { swipeDrag = null; return; }
  const { row, dragging, dx } = swipeDrag;
  if (dragging) {
    if (dx < -SWIPE_ACTIONS_WIDTH / 2) revealSwipeRow(row); else hideSwipeRow(row);
    // Chặn onclick (event "click" bắn sau "pointerup") mở nhầm BookDetailActivity ngay sau khi vuốt.
    row.dataset.justDragged = '1';
    setTimeout(() => { delete row.dataset.justDragged; }, 300);
  }
  swipeDrag = null;
}

function revealSwipeRow(row) {
  row.classList.add('swipe-open');
  row.querySelector('.book-card').style.transform = `translateX(-${SWIPE_ACTIONS_WIDTH}px)`;
  openSwipeRowEl = row;
}
function hideSwipeRow(row) {
  row.classList.remove('swipe-open');
  row.querySelector('.book-card').style.transform = '';
  if (openSwipeRowEl === row) openSwipeRowEl = null;
}

// Gọi bởi onclick của .book-card khi đang bọc trong .swipe-row (list view). Nếu hàng đang lộ 2
// nút, 1 tap ở phần thẻ chỉ nên ĐÓNG lại, không mở BookDetailActivity.
function handleCardTap(bookId) {
  const row = document.querySelector(`.swipe-row[data-book-id="${bookId}"]`);
  if (row && row.dataset.justDragged) return;
  if (row && row.classList.contains('swipe-open')) { hideSwipeRow(row); return; }
  openBookDetailNative(bookId);
}

// 2 nút hành động khi vuốt lộ ra -- gọi thẳng AndroidBridge, không qua BookDetailActivity.
function handleSwipeAction(bookId, format, kind) {
  const row = document.querySelector(`.swipe-row[data-book-id="${bookId}"]`);
  if (row) hideSwipeRow(row);
  if (!format) return;
  if (kind === 'open') {
    if (window.AndroidBridge && typeof window.AndroidBridge.downloadAndOpenBook === 'function') {
      window.AndroidBridge.downloadAndOpenBook(bookId, format);
    }
  } else {
    if (window.AndroidBridge && typeof window.AndroidBridge.downloadAndShareBook === 'function') {
      window.AndroidBridge.downloadAndShareBook(bookId, format);
    }
  }
}

document.addEventListener('DOMContentLoaded', initSwipeGestures);

@echo off
setlocal EnableDelayedExpansion

set "GITHUB_USER=nguyenphanvn95"
set "REPO_NAME=calibre-sync-upgraded"
set "REPO_URL=https://github.com/nguyenphanvn95/calibre-sync-upgraded.git"
set "PRIVATE_REPO=false"

cd /d "%~dp0"

where git >nul 2>nul
if errorlevel 1 (
    echo Git chua duoc cai dat hoac chua co trong PATH.
    pause
    exit /b 1
)

where curl >nul 2>nul
if errorlevel 1 (
    echo Curl chua co trong PATH. Windows 10/11 thuong co san curl.
    pause
    exit /b 1
)

if "%GITHUB_TOKEN%"=="" (
    echo.
    echo Can GitHub Personal Access Token de tu dong tao repo va push.
    echo Token can quyen: repo
    echo Tao token tai: https://github.com/settings/tokens
    echo.
    set /p "GITHUB_TOKEN=Nhap GitHub token cua tai khoan %GITHUB_USER%: "
)

if "%GITHUB_TOKEN%"=="" (
    echo Ban chua nhap token, khong the tu dong tao repo/push.
    pause
    exit /b 1
)

echo.
echo Kiem tra tai khoan GitHub dang dung token...
curl -s -H "Authorization: Bearer %GITHUB_TOKEN%" -H "Accept: application/vnd.github+json" https://api.github.com/user > "%TEMP%\github_user.json"
findstr /C:"\"login\": \"%GITHUB_USER%\"" "%TEMP%\github_user.json" >nul
if errorlevel 1 (
    echo Token khong thuoc tai khoan %GITHUB_USER% hoac khong hop le.
    echo Noi dung tra ve:
    type "%TEMP%\github_user.json"
    del "%TEMP%\github_user.json" >nul 2>nul
    pause
    exit /b 1
)
del "%TEMP%\github_user.json" >nul 2>nul

echo.
echo Kiem tra repo GitHub...
curl -s -H "Authorization: Bearer %GITHUB_TOKEN%" -H "Accept: application/vnd.github+json" "https://api.github.com/repos/%GITHUB_USER%/%REPO_NAME%" > "%TEMP%\github_repo.json"
findstr /C:"\"full_name\": \"%GITHUB_USER%/%REPO_NAME%\"" "%TEMP%\github_repo.json" >nul
if not errorlevel 1 (
    echo Repo da ton tai.
    del "%TEMP%\github_repo.json" >nul 2>nul
) else (
    findstr /C:"\"message\": \"Not Found\"" "%TEMP%\github_repo.json" >nul
    if errorlevel 1 (
        echo Khong kiem tra duoc repo. Noi dung tra ve:
        type "%TEMP%\github_repo.json"
        del "%TEMP%\github_repo.json" >nul 2>nul
        pause
        exit /b 1
    )
    del "%TEMP%\github_repo.json" >nul 2>nul

    echo Repo chua ton tai, dang tao repo %GITHUB_USER%/%REPO_NAME%...
    curl -s -X POST -H "Authorization: Bearer %GITHUB_TOKEN%" -H "Accept: application/vnd.github+json" https://api.github.com/user/repos -d "{\"name\":\"%REPO_NAME%\",\"private\":%PRIVATE_REPO%,\"description\":\"Calibre Sync upgraded Android app\"}" > "%TEMP%\github_create_repo.json"
    findstr /C:"\"full_name\": \"%GITHUB_USER%/%REPO_NAME%\"" "%TEMP%\github_create_repo.json" >nul
    if errorlevel 1 (
        echo Tao repo that bai. Noi dung tra ve:
        type "%TEMP%\github_create_repo.json"
        del "%TEMP%\github_create_repo.json" >nul 2>nul
        pause
        exit /b 1
    )
    del "%TEMP%\github_create_repo.json" >nul 2>nul
)

if not exist ".git" (
    echo Khoi tao git repository...
    git init
)

git remote get-url origin >nul 2>nul
if errorlevel 1 (
    echo Them remote origin...
    git remote add origin "%REPO_URL%"
) else (
    echo Cap nhat remote origin...
    git remote set-url origin "%REPO_URL%"
)

git branch -M main

echo.
echo Cac thay doi hien tai:
git status --short
echo.

git add .

git diff --cached --quiet
if errorlevel 1 (
    set "COMMIT_MSG=Update Calibre Sync"
    if not "%~1"=="" set "COMMIT_MSG=%~1"

    if "!COMMIT_MSG!"=="" set "COMMIT_MSG=Update Calibre Sync"

    echo Commit voi message: "!COMMIT_MSG!"
    git commit -m "!COMMIT_MSG!"
    if errorlevel 1 (
        echo Commit that bai.
        pause
        exit /b 1
    )
) else (
    echo Khong co thay doi moi de commit.
)

echo.
echo Dang push len GitHub...
git -c credential.helper= push "https://%GITHUB_USER%:%GITHUB_TOKEN%@github.com/%GITHUB_USER%/%REPO_NAME%.git" main
if errorlevel 1 (
    echo.
    echo Push that bai. Hay kiem tra repo GitHub da duoc tao chua va ban da dang nhap Git/GitHub chua.
    pause
    exit /b 1
)

echo.
echo Push thanh cong.
pause

@echo off
setlocal

echo Luu GitHub token vao bien moi truong cua Windows.
echo Token can quyen: repo
echo Tao token tai: https://github.com/settings/tokens
echo.
echo Luu y: Khong nen ghi token truc tiep vao file trong project vi co the bi day len GitHub.
echo.

set /p "TOKEN=Nhap GitHub token moi: "

if "%TOKEN%"=="" (
    echo Ban chua nhap token.
    pause
    exit /b 1
)

setx GITHUB_TOKEN "%TOKEN%"

echo.
echo Da luu GITHUB_TOKEN vao bien moi truong cua user Windows.
echo Hay dong cua so CMD/PowerShell hien tai, mo lai cua so moi, roi chay push_to_github.bat.
pause

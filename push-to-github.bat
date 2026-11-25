@echo off
echo ========================================================
echo   Evi Unofficial - Push to GitHub Helper
echo ========================================================
echo.
echo Make sure you have created an empty repository on GitHub:
echo https://github.com/new
echo (Name: evi-unofficial, Public, do NOT check README/license)
echo.
set /p REPO_URL="Enter your GitHub repository URL (e.g. https://github.com/USERNAME/evi-unofficial.git): "
if "%REPO_URL%"=="" (
    echo [ERROR] No URL provided. Aborting.
    pause
    exit /b 1
)

echo.
echo Configuring remote origin...
git remote remove origin 2>nul
git remote add origin %REPO_URL%
git branch -M main

echo.
echo Pushing code to GitHub...
git push -u origin main

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo [SUCCESS] Your repository is now live on GitHub!
    echo Check your repository page: %REPO_URL%
    echo GitHub Actions will automatically start testing and scanning.
    echo ========================================================
) else (
    echo.
    echo [NOTE] If git requested credentials, sign in with your GitHub account
    echo or use a GitHub Personal Access Token (PAT).
)
echo.
pause

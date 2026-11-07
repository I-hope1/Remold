@echo off
rem Windows-native entry point for verify-recon.sh (the runner shells out via cmd, where a bare
rem `bash` can be WSL and cannot run the POSIX verifier).
setlocal
set "GIT_BASH="
if exist "E:\Program Files\Git\bin\bash.exe" set "GIT_BASH=E:\Program Files\Git\bin\bash.exe"
if not defined GIT_BASH if exist "%ProgramFiles%\Git\bin\bash.exe" set "GIT_BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined GIT_BASH (
  echo [recon] FAILED: Git Bash not found; set GIT_BASH to a Git for Windows bash.exe 1>&2
  exit /b 2
)
"%GIT_BASH%" "%~dp0verify-recon.sh" %*
exit /b %ERRORLEVEL%

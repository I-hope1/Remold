@echo off
rem Windows-native entry point for verify-evidence.sh.
rem
rem The battle/CI runner shells out through cmd, where a bare `bash` may be WSL (this machine's
rem `bash` resolves to WSL), and WSL cannot use the Windows JDK/JVM the probe needs. Delegate to the
rem Git Bash copy that actually has the POSIX tools, so the acceptance command needs no quoting and
rem no shell-specific path.
setlocal
set "GIT_BASH="
if exist "E:\Program Files\Git\bin\bash.exe" set "GIT_BASH=E:\Program Files\Git\bin\bash.exe"
if not defined GIT_BASH if exist "%ProgramFiles%\Git\bin\bash.exe" set "GIT_BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined GIT_BASH if exist "%LocalAppData%\Programs\Git\bin\bash.exe" set "GIT_BASH=%LocalAppData%\Programs\Git\bin\bash.exe"
if not defined GIT_BASH (
  echo [verify] FAILED: Git Bash not found; set GIT_BASH to a Git for Windows bash.exe 1>&2
  exit /b 2
)
"%GIT_BASH%" "%~dp0verify-evidence.sh" %*
exit /b %ERRORLEVEL%

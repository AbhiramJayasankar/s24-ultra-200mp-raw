@echo off
setlocal
if not exist "%JAVA_HOME%\include\jni.h" set "JAVA_HOME=C:\Program Files\Java\jdk-25"
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
if errorlevel 1 exit /b %errorlevel%
if not exist build\lumdir-host mkdir build\lumdir-host
cl /nologo /LD /O2 /fp:strict /MT /EHsc /std:c++17 /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" /Fo"build/lumdir-host/" /Fe:"build\lumdir-host\s24lumdir.dll" app\src\main\cpp\lumdir_median.cc app\src\main\cpp\lumdir_directional.cc
exit /b %errorlevel%

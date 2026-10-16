set COMMON=-I"%JAVA_HOME%/include" -I"%JAVA_HOME%/include/win32" -std=c++23 -O3 -g0 -fno-exceptions -fno-rtti -fdeclspec -w

zig c++ -shared -target x86_64-windows -Wl,--strip-all %COMMON% -o ./libs/tool64.dll tool.cpp
zig c++ -shared -target aarch64-macos -Wl,-dead_strip %COMMON% -o ./libs/libtoolarm64.dylib tool.cpp
zig c++ -shared -target x86_64-macos -Wl,-dead_strip %COMMON% -o ./libs/libtool64.dylib tool.cpp
zig c++ -shared -target x86_64-linux-gnu.2.17 -Wl,--strip-all %COMMON% -o ./libs/libtool64.so tool.cpp
#!/bin/sh
# Fixed launcher: no caller arguments, no ambient JVM options, no credential interpolation.
set +x
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH
[ "$#" -eq 0 ] || exit 64
cd "$(dirname "$0")" || exit 70
exec java -Xms32m -Xmx192m -jar target/maintenance-launcher-1.0.jar

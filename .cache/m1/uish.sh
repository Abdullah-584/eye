#!/system/bin/sh
# Dump visible text nodes of the current screen (run on device).
uiautomator dump /sdcard/ui-dump.xml >/dev/null 2>&1
cat /sdcard/ui-dump.xml

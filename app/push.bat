adb push debug/app.apk /data/local/tmp/app.apk
adb shell pm install -i "com.android.vending" -r /data/local/tmp/app.apk
adb shell rm /data/local/tmp/app.apk
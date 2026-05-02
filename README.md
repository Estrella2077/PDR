# 涔濊酱IMU-PDR绀轰緥

璇ョ洰褰曚笅鏄竴涓嫭绔嬬殑 Android Studio 宸ョ▼锛屽叏閮ㄥ唴瀹归兘鏀惧湪 `PDR/` 涓紝娌℃湁淇敼 `PDR-main` 鍜?`SensorCollectorWatch-main`銆?

## 宸插疄鐜板姛鑳?

- 閲囬泦鎵嬫満涔濊酱 IMU 涓殑涓夌被鏍稿績鍘熷鏁版嵁锛?
  - 鍔犻€熷害璁?
  - 闄€铻轰华
  - 纾佸姏璁?
- 浣跨敤 `PDR-main` 鍚岀被鐧惧害鍦板浘搴曞浘锛屾敮鎸佹墜鍔跨缉鏀?
- 鏀寔涓夌瀹氫綅妯″紡锛?
  - `PDR`
  - `鍗槦瀵艰埅`
  - `PDR + 鍗槦瀵艰埅`
- 闀挎寜鍦板浘璁剧疆 PDR 璧风偣
- 璁板綍鍘熷浼犳劅鍣ㄦ暟鎹埌 `imu_raw.csv`
- 璁板綍姝ョ骇 PDR 杈撳嚭鍒?`pdr_steps.csv`
- 妯珫灞忓垏鎹㈡椂涓嶉噸寤?Activity锛屼細璇濅笉涓柇

## 鏁版嵁淇濆瓨浣嶇疆

浼氳瘽鏂囦欢榛樿鍐欏叆锛?

`Android/data/com.example.imupdr/files/Documents/imu_pdr_sessions/<鏃堕棿鎴?/`

姣忔浼氳瘽鍖呭惈锛?

- `imu_raw.csv`
- `pdr_steps.csv`

## 鏍稿績鏂囦欢

- `app/src/main/java/com/example/imupdr/MainActivity.kt`
- `app/src/main/java/com/example/imupdr/PdrProcessor.kt`
- `app/src/main/java/com/example/imupdr/CsvSessionWriter.kt`
- `app/src/main/java/com/example/imupdr/Transer.java`

## 浣跨敤鏂瑰紡

1. 鐢?Android Studio 鎵撳紑 `PDR/`
2. 绛夊緟 Gradle 鍚屾瀹屾垚
3. 瀹夎鍒板甫鍔犻€熷害璁°€侀檧铻轰华銆佺鍔涜鍜?GNSS 鐨?Android 鎵嬫満
4. 鏍规嵁闇€瑕侀€夋嫨 `PDR / 鍗槦瀵艰埅 / PDR+鍗槦`
5. 濡傞渶绾?PDR锛屽缓璁厛闀挎寜鍦板浘璁剧疆璧风偣
6. 鐐瑰嚮鈥滃紑濮嬮噰闆嗏€?

## 褰撳墠闄愬埗

- 褰撳墠铻嶅悎妯″紡鏄交閲忕骇浣嶇疆鏍℃锛屼笉鏄畬鏁村崱灏旀浖铻嶅悎
- PDR 姝ラ暱妯″瀷鍜屽嘲鍊奸槇鍊间粛闇€鎸夎澶囧拰鎼哄甫鏂瑰紡缁х画璋冨弬
- 鑸悜浠嶄互鍔犻€熷害璁?+ 纾佸姏璁′负涓伙紝闄€铻轰华褰撳墠涓昏鐢ㄤ簬鍘熷鏁版嵁閲囬泦

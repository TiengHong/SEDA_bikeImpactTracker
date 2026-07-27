#include <Wire.h>
#include <MPU6050_light.h>
#include <TinyGPSPlus.h>
#include <HardwareSerial.h>
#include <FS.h>
#include <LittleFS.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// 1. BLE NUS (Nordic UART Service) UUIDs
#define SERVICE_UUID           "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_UUID_RX "6e400002-b5a3-f393-e0a9-e50e24dcca9e" // Write
#define CHARACTERISTIC_UUID_TX "6e400003-b5a3-f393-e0a9-e50e24dcca9e" // Notify

#include <ThreeWire.h>
#include <RtcDS1302.h>

// 2. Objects Definition
MPU6050 mpu(Wire);
TinyGPSPlus gps;
HardwareSerial gpsSerial(2); // RX2 = Pin 16, TX2 = Pin 17

// RTC DS1302 Pins
#define RTC_CLK_PIN 13
#define RTC_IO_PIN  12
#define RTC_RST_PIN 14

ThreeWire myWire(RTC_IO_PIN, RTC_CLK_PIN, RTC_RST_PIN); // IO/DAT, SCLK/CLK, CE/RST
RtcDS1302<ThreeWire> rtc(myWire);

BLEServer *pServer = NULL;
BLECharacteristic *pTxCharacteristic = NULL;
BLECharacteristic *pRxCharacteristic = NULL;

// 3. File System Configuration
const char* LOG_FILE_PATH = "/vibration_log.csv";

// 4. Vibration Detection Thresholds & Config
const float VIBRATION_THRESHOLD_G = 2.5; // Impact G threshold (1.0G = static gravity)
unsigned long lastVibrationTime = 0;
const unsigned long DEBOUNCE_DELAY = 2000; // Debounce delay (2 seconds) to avoid rapid duplicate logging

// 5. Connection State Tracking
bool deviceConnected = false;
bool oldDeviceConnected = false;

// Function declarations
void sendLine(String line);
void sendAllLogsToBLE();
void clearAllLogs();

// 6. BLE Server Connection Callbacks
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println("\n BLE Client Connected!");
    };

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println("\n BLE Client Disconnected!");
    }
};

// 7. BLE Characteristic Write Callbacks (Receiving commands from App)
class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      // getValue() returns String in newer ESP32 cores, std::string in older ones.
      // Calling .c_str() compiles perfectly on both because both classes implement it.
      String rxValue = pCharacteristic->getValue().c_str();
      rxValue.trim();

      if (rxValue.length() > 0) {
        Serial.print(" BLE Command Received: ");
        Serial.println(rxValue);

        if (rxValue == "REQ_DATA") {
          sendAllLogsToBLE();
        } 
        else if (rxValue == "ACK_DATA") {
          clearAllLogs();
        }
      }
    }
};

// 8. LittleFS Filesystem Init
void initLittleFS() {
  if (!LittleFS.begin(true)) {
    Serial.println("[-] LittleFS mount failed!");
    return;
  }
  Serial.println("[+] LittleFS Filesystem mounted successfully.");
  
  if (!LittleFS.exists(LOG_FILE_PATH)) {
    File file = LittleFS.open(LOG_FILE_PATH, FILE_WRITE);
    if (file) {
      file.println("Timestamp,Latitude,Longitude,Impact_G"); // Write CSV Header
      file.close();
      Serial.println("[+] Created new log file: /vibration_log.csv");
    }
  }
}

// Append offline logs to flash memory
void appendLog(String timestamp, double lat, double lng, float impactG) {
  File file = LittleFS.open(LOG_FILE_PATH, FILE_APPEND);
  if (!file) {
    Serial.println("[-] Failed to open log file for appending!");
    return;
  }
  
  String dataLine = timestamp + "," + String(lat, 6) + "," + String(lng, 6) + "," + String(impactG, 2);
  file.println(dataLine);
  file.close();
  
  Serial.println(" Offline Mode: Buffered shock event to local LittleFS flash:");
  Serial.println("        " + dataLine);
}

// Send a single line over BLE Notify (TX Characteristic) with automatic trailing newline delimiter
void sendLine(String line) {
  if (deviceConnected && pTxCharacteristic != NULL) {
    String lineWithNewline = line + "\n";
    pTxCharacteristic->setValue(lineWithNewline.c_str());
    pTxCharacteristic->notify();
    delay(50); // Safe delay to prevent packet congestion/loss on BLE stacks
  }
}

// Send all stored logs over BLE
void sendAllLogsToBLE() {
  Serial.println(" Transferring accumulated logs to BLE client...");
  sendLine("===BATCH_START===");
  
  File file = LittleFS.open(LOG_FILE_PATH, FILE_READ);
  if (!file) {
    sendLine("===BATCH_END===");
    return;
  }
  
  // Skip CSV header line if present
  if (file.available()) {
    String header = file.readStringUntil('\n');
  }

  while (file.available()) {
    String line = file.readStringUntil('\n');
    line.trim();
    if (line.length() > 0) {
      sendLine(line);
      Serial.println("        Sent: " + line);
    }
  }
  file.close();
  
  sendLine("===BATCH_END===");
  Serial.println(" Batch data transfer completed.");
}

// Clear local logs upon receiving verification (ACK_DATA) from app
void clearAllLogs() {
  if (LittleFS.remove(LOG_FILE_PATH)) {
    Serial.println("[🧹] Client verified reception. Wiped local log file.");
    File file = LittleFS.open(LOG_FILE_PATH, FILE_WRITE);
    if (file) {
      file.println("Timestamp,Latitude,Longitude,Impact_G");
      file.close();
    }
    sendLine("CLEAR_SUCCESS");
    Serial.println("[🧹] Local log file re-initialized.");
  } else {
    Serial.println("[-] Error: Failed to clear log file!");
    sendLine("CLEAR_FAILED");
  }
}

void setup() {
  Serial.begin(115200);
  delay(1500); // Guard delay

  // Initialize RTC
  rtc.Begin();

  RtcDateTime compiled = RtcDateTime(__DATE__, __TIME__);
  if (!rtc.IsDateTimeValid()) {
    Serial.println("[RTC] lost confidence in DateTime! Initializing with compile time...");
    rtc.SetDateTime(compiled);
  }

  if (rtc.GetIsWriteProtected()) {
    Serial.println("[RTC] was write protected, enabling writing now...");
    rtc.SetIsWriteProtected(false);
  }

  if (!rtc.GetIsRunning()) {
    Serial.println("[RTC] was not running, starting now...");
    rtc.SetIsRunning(true);
  }

  // Print current time for verification
  if (rtc.IsDateTimeValid()) {
    RtcDateTime now = rtc.GetDateTime();
    Serial.printf("[RTC] Configured time: %04d-%02d-%02d %02d:%02d:%02d\n",
                  now.Year(), now.Month(), now.Day(),
                  now.Hour(), now.Minute(), now.Second());
  } else {
    Serial.println("[-] RTC read check failed! Verify pin configurations.");
  }

  // Initialize Filesystem
  initLittleFS();

  // Initialize I2C for MPU-6050
  Wire.begin();
  byte mpuStatus = mpu.begin();
  if (mpuStatus != 0) {
    Serial.print("[-] MPU-6050 initialization failed! Error code: ");
    Serial.println(mpuStatus);
    while (1) { delay(10); }
  }
  
  Serial.println("[+] MPU-6050 sensor calibrated. Do not move the board...");
  delay(1000);
  mpu.calcOffsets(true, true);
  Serial.println("[+] Sensor calibration complete!");

  // Initialize GPS HW Serial (RX2=16, TX2=17)
  gpsSerial.begin(9600, SERIAL_8N1, 16, 17);
  Serial.println("[+] NEO-6M GPS Module connected at 9600 baud.");

  // Initialize BLE Device
  BLEDevice::init("ESP32_Vibe_Tracker");

  // Create BLE Server
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  // Create BLE Service
  BLEService *pService = pServer->createService(SERVICE_UUID);

  // Create TX Characteristic (Notify)
  pTxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_TX,
                        BLECharacteristic::PROPERTY_NOTIFY
                      );
  pTxCharacteristic->addDescriptor(new BLE2902());

  // Create RX Characteristic (Write)
  pRxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_RX,
                        BLECharacteristic::PROPERTY_WRITE
                      );
  pRxCharacteristic->setCallbacks(new MyCallbacks());

  // Start BLE Service
  pService->start();

  // Start Advertising
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);  // help with iPhone connections issues
  pAdvertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println(" BLE active & advertising as: 'ESP32_Vibe_Tracker'");
}

unsigned long lastRtcSyncTime = 0;
const unsigned long RTC_SYNC_INTERVAL = 300000; // Sync RTC with GPS every 5 minutes when GPS is valid

void loop() {
  // 1. Handle BLE connection state transitions dynamically
  if (!deviceConnected && oldDeviceConnected) {
    delay(500); // give the bluetooth stack the chance to get ready
    pServer->startAdvertising(); // restart advertising
    Serial.println(" BLE Advertising restarted.");
    oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
    // Connection established
    oldDeviceConnected = deviceConnected;
  }

  // 2. Parse incoming GPS telemetry stream
  while (gpsSerial.available() > 0) {
    gps.encode(gpsSerial.read());
  }

  // Sync RTC with GPS periodically if GPS date, time, and location are valid
  if (gps.location.isValid() && gps.date.isValid() && gps.time.isValid()) {
    unsigned long currentTime = millis();
    if (lastRtcSyncTime == 0 || (currentTime - lastRtcSyncTime > RTC_SYNC_INTERVAL)) {
      int year = gps.date.year();
      int month = gps.date.month();
      int day = gps.date.day();
      int hour = gps.time.hour() + 9; // Calculate KST (UTC+9)
      int minute = gps.time.minute();
      int second = gps.time.second();

      int daysInMonth[] = { 0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
      if ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)) {
        daysInMonth[2] = 29;
      }
      if (hour >= 24) {
        hour -= 24;
        day++;
        if (day > daysInMonth[month]) {
          day = 1;
          month++;
          if (month > 12) {
            month = 1;
            year++;
          }
        }
      }

      RtcDateTime dt(year, month, day, hour, minute, second);
      rtc.SetDateTime(dt);
      lastRtcSyncTime = currentTime;
      Serial.printf("[RTC] Synced RTC with GPS (KST): %04d-%02d-%02d %02d:%02d:%02d\n", year, month, day, hour, minute, second);
    }
  }

  // 3. Update MPU-6050 Accelerometer
  mpu.update();
  float ax = mpu.getAccX();
  float ay = mpu.getAccY();
  float az = mpu.getAccZ();
  float totalAccG = sqrt(ax*ax + ay*ay + az*az);

  // 4. Threshold trigger event evaluation
  if (totalAccG > VIBRATION_THRESHOLD_G) {
    unsigned long currentTime = millis();
    
    if (currentTime - lastVibrationTime > DEBOUNCE_DELAY) {
      lastVibrationTime = currentTime;

      Serial.println("\n==================================================");
      Serial.printf("[Shock Detected] Magnitude: %.2f G\n", totalAccG);

      double lat = 37.1806928; // [학교] 주변 기본 테스트 좌표 (School)
      double lng = 127.040918;
      String timestamp = "";
      bool usingFallback = false;

      if (gps.location.isValid()) {
        lat = gps.location.lat();
        lng = gps.location.lng();
      } else {
        usingFallback = true;
        // 실내 테스트용: [학교] 주변 약 50m 반경 무작위 오차 부여
        // 위도: 50m ≈ 0.000450도 (random(-450, 450) / 1000000.0)
        // 경도: 50m ≈ 0.000560도 (random(-560, 560) / 1000000.0)
        float randomOffsetLat = (random(-450, 450) / 1000000.0);
        float randomOffsetLng = (random(-560, 560) / 1000000.0);
        lat += randomOffsetLat;
        lng += randomOffsetLng;
      }

      // Try to determine the most accurate timestamp available
      bool timeAcquired = false;

      // Priority 1: GPS Atomic Time (KST UTC+9)
      if (gps.date.isValid() && gps.time.isValid()) {
        int year = gps.date.year();
        int month = gps.date.month();
        int day = gps.date.day();
        int hour = gps.time.hour() + 9;
        int minute = gps.time.minute();
        int second = gps.time.second();

        int daysInMonth[] = { 0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
        if ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)) {
          daysInMonth[2] = 29;
        }
        if (hour >= 24) {
          hour -= 24;
          day++;
          if (day > daysInMonth[month]) {
            day = 1;
            month++;
            if (month > 12) {
              month = 1;
              year++;
            }
          }
        }

        char timeBuffer[25];
        sprintf(timeBuffer, "%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second);
        timestamp = String(timeBuffer);
        timeAcquired = true;
        Serial.println(" [Time Source] GPS Atomic Time (RTC Calibration Source)");
      }
      // Priority 2: Stored real-time from the DS1302 RTC module
      else {
        if (rtc.IsDateTimeValid()) {
          RtcDateTime now = rtc.GetDateTime();
          char timeBuffer[25];
          sprintf(timeBuffer, "%04d-%02d-%02d %02d:%02d:%02d", now.Year(), now.Month(), now.Day(), now.Hour(), now.Minute(), now.Second());
          timestamp = String(timeBuffer);
          timeAcquired = true;
          Serial.println(" [Time Source] DS1302 Hardware RTC Module");
        }
      }

      // Priority 3: 가동 시간 fallback (when neither GPS nor RTC is functional)
      if (!timeAcquired) {
        unsigned long sec = millis() / 1000;
        int hour = (12 + (sec / 3600)) % 24;
        int minute = (sec / 60) % 60;
        int second = sec % 60;
        char timeBuffer[25];
        sprintf(timeBuffer, "2026-07-08 %02d:%02d:%02d", hour, minute, second);
        timestamp = String(timeBuffer);
        Serial.println(" [Time Source] GPS/RTC Unavailable - Millis Offset Fallback");
      }

      if (usingFallback) {
        Serial.println("[실내 GPS Fallback 모드] 위성 신호를 탐색 중입니다. 테스트용 서울역 좌표를 전송합니다.");
      }

      // Send immediately or accumulate locally based on connection status
      if (deviceConnected) {
        String rtLine = "RT," + timestamp + "," + String(lat, 6) + "," + String(lng, 6) + "," + String(totalAccG, 2);
        sendLine(rtLine);
        Serial.println(" Real-time Mode: Direct wireless telemetry broadcasted.");
        Serial.println("        " + rtLine);
      } else {
        appendLog(timestamp, lat, lng, totalAccG);
      }
      Serial.println("==================================================");
    }
  }
  
  // Tiny delay to breathe
  delay(10);
}

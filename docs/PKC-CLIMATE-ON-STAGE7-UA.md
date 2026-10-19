# Fisker Warm, етап 7: авторизований локальний CLIMATE ON

20.10.2026, Europe/Kyiv. Fisker Ocean 2023 EU, Ocean OS 2.1, PKC 21.3; S24 Ultra / Android 16, S21, Vgate FD-IOS BLE. Досліджено попередні звіти, issue #3/#4, PR #2/#5, оригінальний збережений OceanLink Pro 1.2.6 та актуальні публічні джерела.

**Реальний запуск клімату не реалізовано. Критерій етапу 7 не виконаний.** Немає автомобільного прототипу, нового APK або фактичного тесту HVAC. Статично підтверджено локальний start-only шлях через PKC, але для незалежного авторизованого виклику бракує чинного власного VA/BLE-ключа та перевірки протоколу на цьому PKC. Наявний код отримання ключа використовує серверний SecurityAccess і RoutineControl; його не можна переносити у дозволений Read Only шлях.

## 1. Які механізми перевірено

| Механізм | Доказ | Рішення |
| --- | --- | --- |
| Прямий PKC BLE | Реалізація `remoteVehicleClimateOn_SendCMD` у DEX; телефон GATT-server, PKC central; challenge/freshness/HMAC [S1, S2]. | Єдиний конкретний кандидат для незалежного локального Warm. Умовно придатний, ще не працездатний на авто власника. |
| ECC / PKC через Vgate OBD | Історичні ідентичності ECC `7F0/7F8`, PKC `7A2/7AA`; попередній аудит діагностики [S3]. | Немає підтвердженого start-only через OBD. Діагностичний доступ не є дозволом запуску. BLE-payload не переноситься на ці CAN-адреси. |
| Зовнішній виклик OceanLink через Android | Flutter MethodChannel є внутрішнім каналом застосунку; `ForegroundBleService` і `BleActionReceiver` мають `exported=false` [S1]. | Не знайдено документованого незалежного intent/Binder API. Не обходимо перевірки іншого застосунку; OceanLink не стає обов'язковим компонентом. |
| O Loop / штатна телематика | Провайдер заявляє preconditioning, але виключає підтримку legacy сторонніх API [S5]. | Підтримуваного інтерфейсу для Warm на нашій конфігурації не встановлено. Платний сервіс не пропонується як виконання завдання. |
| Ocean Pulse / Home Assistant | Актуальний commit від 19.10 містить `PKC_CLIMATE_ON`, `durationMinutes: 15`; це device cloud API для Pulse [S6]. | Не є прямим PKC BLE-клієнтом для наявного Samsung. Не виправдовує купівлю Pulse. |
| Fiskeretta, SaavyScripts, CAN | Перевірені актуальні дерева; commits збігаються зі збереженим дослідженням ключа [S2, S7]. | Не знайдено незалежного дозволеного key provisioning або перевіреної команди локального клімату. Повторного CAN-сканування немає. |

Важливе уточнення до старого етапу 4: Home Assistant Pulse вже змінився. Commit `842a98ce0af959bec2a42796e59db563de8696d5` містить 11 нових commits відносно дослідженого `ee9cac...`; автор повідомляє про встановлений Pulse і beta. Старий hardcoded статус за HTTP 401 вилучений. Цей давній недолік більше не подається як стан актуального клієнта. Проте залишаються сторонній Pulse-пристрій/API та непідтверджений незалежний PKC-канал. Контрольні API не викликалися.

## 2. Що відомо про справжній start-only

Простежений ланцюг: Flutter `remoteVehicleClimateOn` → coroutine wrapper → `ScuSdkImpl.remoteVehicleClimateOn_SendCMD` → `BleGattSendDataHandler.sendDataToPKC` → GATT notification підключеному PKC [S1]. Це локальний канал Bluetooth телефона; Vgate не передає цей виклик.

Вхідні параметри обгортки: `turnOn=true`, `durationMinutes=15`. Start-метод не має аргументів температури, вентилятора чи напрямку обдуву. Це **не доводить**, що PKC відновить останні налаштування: повний Flutter-сценарій, внутрішня прошивка PKC та взаємодія з ECC не виміряні. Інший `_SendTime` не підставляється замість `_SendCMD`.

SDK готує пакет із типом, довжиною, даними, challenge від PKC, freshness і HMAC-SHA256. Він перевіряє стан з'єднання та наявність challenge. `runTheConnection` очікує 32 hex-символи, тобто 16-байтовий VA-key, і використовує його для автентифікації та advertising. Звичайне Android Bluetooth pairing не створює цей автомобільний секрет [S1, S2].

Відомий зовнішній приймач - PKC. ECC є кліматичним контролером. Внутрішній маршрут, остаточний арбітр запуску та повні міжблокові interlock-умови з APK не встановлюються. Не вигадується маршрут через TBOX, gateway або ICC.

Офіційні Android GATT/advertising API дозволяють реалізувати потрібну роль телефона. Їхня наявність не дає автомобільного credential. Повернення успіху `notifyCharacteristicChanged` означає запуск Bluetooth-операції, а не виконання CLIMATE ON [S8].

## 3. Критична залежність: отримання власного ключа

Знайдено і прочитано окремий звіт від 12.10: `PKC21.3-BLE-Key-Research-UA.md` та його Evidence ZIP [S2]. У ньому вже простежено справжній callback кнопки отримання ключа, а не лише її назву. На етапі 7 повний AOT-парсер повторно не запускався: повторно звірено вихідний бінарний файл, цілісність архіву та шість ARM BL-переходів.

| Перехід у `libapp.so` | Підтверджена прив'язка |
| --- | --- |
| `0xa87fbc → 0xa88b98` | Callback кнопки → процедура отримання ключа. |
| `0xa88bec → 0xa88ebc` | Reader → обгортка захищеного доступу. |
| `0xa88ef8 → 0xa854ec` | Обгортка → generic діагностична сесія. |
| `0xa85cd4 → 0xa79c2c` | Захищена сесія → HTTP-helper. |
| `0xa79d84 → 0xa7ab80` | HTTP-helper → заголовки авторизації акаунта. |
| `0xa88098 → 0xa88218` | Callback успішного читання → оновлення серверного запису. |

За збереженим AOT-розбором, процедура перед читанням отримує security seed, звертається до backend для `signature`, надсилає результат PKC та переходить у extended session. Саме отримання секрету виконане RoutineControl, **не ReadDataByIdentifier**. Окремий `updateblekey` відбувається після цього; вилучення синхронізації профілю не усуває попередньої серверної залежності.

Тому тут немає дозволеної автономної операції «прочитати один DID через наш Vgate». SecurityAccess, сесії й RoutineControl не додаються в Warm; серверні ключі, токени OceanLink і алгоритми обходу не використовуються. Приватних запитів до діагностичного API не було.

FAQ одночасно визначає BLE-key як credential і рекламує PKC21 як такий, що його не потребує [S4]. Код reader і native SDK усе одно використовує ключ. Можливе пояснення - автоматизація отримання ключа замість ручного пошуку старого cloud-key. Це інтерпретація, а не доведений keyless режим PKC21.3.

Для вже законно отриманого чинного VA-key досліджений native BLE-шлях не містить FOA-запиту на кожне з'єднання [S2]. Отже, незалежний локальний клієнт технічно має сенс. Але постійний строк ключа і прийняття саме цим PKC не доведені. Старий 30-денний ліміт належить клієнтському offline-login OceanLink, а не підтвердженому строку дії секрету PKC.

У досліджених матеріалах справжнього власного VA-key немає. JSON Remote/Gateway з етапу 6 містять ключі інтернет-шлюзу; вони не є ключем PKC і не можуть його замінити. Власність на автомобіль не дозволяє математично вивести секрет із VIN. Водночас відсутність відкритого SDK не доводить неможливості незалежної реалізації і не означає автоматичної необхідності купувати SDK чи передплату.

## 4. Чому знайдене підтвердження може бути хибним

Нова перевірка первинного smali показала важливий нюанс: у `BleGattReceiveDataHandler.handleSingleStatusCommand` після розбору fan speed записується `_ClimateControlState`. Для `FanSpeed1...FanSpeed7` він стає `On`; решта гілок у цій ділянці дає `Off`. Це розрахований індикатор вентилятора, а не окреме підтвердження remote-start або роботи нагрівача. Ділянка: рядки 2598–2622; enum mapping і switch перевірені [S1, S9].

`getClimateState` читає `StateFlow.getValue()` і додає `System.currentTimeMillis()`. Час відповіді кешу не доводить новий RX автомобіля. Wrapper start повертає `success(true)` після виклику, тоді як sender може вийти без передачі за порожнього challenge. Немає підстав показувати «Клімат увімкнено» за цими подіями.

| Рівень | Що дозволено стверджувати |
| --- | --- |
| Сформовано | Створено запит у програмі; автомобілю нічого не доведено. |
| Передано | Транспорт виконав операцію; це ще не automotive ACK. |
| PKC прийняв | Потрібна визначена відповідь PKC, прив'язана до запиту. У дослідженого wrapper такого доказу немає. |
| Авто виконало | Потрібен новий визначений автомобільний статус після запиту; timestamp кешу непридатний. |
| HVAC працює | Потрібні спостереження штатної роботи та зіставлений свіжий стан. Fan-On окремо не підтверджує нагрівання чи remote-start. |

## 5. Реалізація та виконані перевірки

Автомобільний прототип **не створений**, оскільки умова його розробки - підтверджений доступний власнику інтерфейс - не виконана. Не включалися автомобільний sender, SDK, нові ECU-команди, key reader або APK. HTTPS relay, сторінка прив'язки, S24↔S21 і живлення Gateway не розвивалися.

Додано тільки відтворюваний read-only аудит `scripts/audit_pkc_stage7.py` та результати `verification/pkc-stage7-static-audit.json`. Скрипт читає збережені файли і smali; не має Bluetooth/OBD/мережевого транспорту. Це інструмент перевірки доказів, не реалізація запуску.

- SHA-256 оригінального XAPK, base APK, ARM32 `libapp.so` і двох evidence ZIP збіглися.
- Усі **21 метод і відповідні файли smali** збіглися з попереднім індексом.
- Звірені **39 записів** внутрішнього SHA256SUMS архіву дослідження ключа.
- На байтах оригінального ELF звірені **6 ARM BL-переходів** захищеного reader-flow.
- Перевірені залежність ClimateState від fan speed і локальний timestamp кешу.
- Наявні офлайн-регресії: **249 успішних перевірок** (152 + 58 + 23 + 16). Ці тести не є новими CAN-скануваннями або вимірюваннями автомобіля.
- Хеші `ReadOnlyPolicy.java` і `HeatSafety.java` незмінні. `main`, PR #2, PR #5 і Android runtime не змінювалися.

Межі: оригінал із збереженого дзеркального зразка 1.2.6; нової офіційної атестації сертифіката немає. Прошивку PKC, автомобільний секрет і живу BLE-сесію не отримано. Історичні 10 PASS patched Jadart не видаються за повторно виконані в цьому етапі. Кількість автомобільних команд - **0**.

## 6. Який механізм обрано і як буде перевірка

Обраний **напрям**: власний локальний BLE peripheral/GATT-server → автентифікований PKC → одна start-only дія. Це умовне технічне рішення, а не готовий механізм. Vgate залишається для чинної діагностики. Доказів необхідності нового обладнання немає.

Після дозволеного отримання ключа потрібно завершити специфікацію GATT/RX/framing та перевірку авторизації, додати лише один start із фіксованою тривалістю і явною згодою. Новий RX має зберігати власний monotonic час/послідовність; похідний fan state не підмінює HVAC feedback. Невідомий результат не повторюється автоматично. Жодні температурні setter-и або proximity unlock/drive не потрібні.

**Умовна єдина процедура приймання; зараз не готова до виконання.** Спочатку потрібні робочий авторизований клієнт і окреме явне погодження власника з конкретною версією та способом припинення:

1. Авто нерухоме в P; для першого локального тесту воно вже awake. Зафіксувати останні налаштування, вимкнути клімат штатно. Ключ у власника, ручне штатне вимкнення доступне. Відсутність нового firmware/ECU writes перевірена до тесту.
2. Один раз натиснути CLIMATE ON з погодженою тривалістю 15 хвилин. Записати окремо формування, передачу, визначений ACK або його відсутність і новий RX. Не повторювати запит за невідомого результату.
3. У контрольному вікні 60 секунд перевірити фактичну штатну роботу та свіжий статус; зафіксувати збереження налаштувань. 60 секунд - критерій тесту, не специфікація Fisker. Саме ввімкнення через штатний екран або відмикання замість команди Warm не зараховується.
4. Під наглядом перевірити штатне завершення. 15 у запиті ще не доводить таймер автомобіля. При відмові, помилці або незавершенні зупинити клімат штатним екраном; від'єднання BLE не вважати stop. Ризики - споживання HV/12 V, відмова запуску/зупинки та зміна стану доступу, якщо універсальний ключ використано неправильно.

Цей перший тест доводитиме тільки локальний запуск на awake авто. Замкнений сон, робота після тривалої стоянки та дистанційність мають окремі критерії; вони не потрібні, щоб спочатку чесно довести основну кнопку.

## 7. Один наступний критичний крок

**Отримати підтверджений одноразовий власницький/сервісний експорт актуального VA/BLE-key вже встановленого PKC21.3 для незалежного локального клієнта, без обов'язкового OceanLink Pro або щорічної передплати.** Не запускати знайдений protected reader із Warm. Якщо експорту не надають, потрібен підтверджений інший owner-authorized SDK/API; нова обгортка цю перешкоду не усуне.

Конкретний первинний адресат - Undercurrent, `info@undercurrentauto.com`, із залученням відповідального за Chevalier PKC21. Контакт вказаний розробником у Google Play [S10]. Підготовлений запит:

> I own a 2023 EU Fisker Ocean with PKC F160808130021.3 (Ocean OS 2.1). Can you provide a supported one-time export of my existing VA/BLE credential, or an owner-authorized independent climate-only API, for a personal Android client without OceanLink Pro/FOA recurring access? No key replacement, firmware change, SecurityAccess implementation, or authentication bypass is requested in our app. Please confirm the supported start-only operation for 15 minutes, last-settings behavior, genuine fresh execution feedback, and vehicle-side timeout/manual stop. Please describe a secure provisioning route rather than sending the key in ordinary email.

Це **підготовлений, не надісланий** запит. Безкоштовна доступність, ціна сервісного експорту й підтримка незалежного клієнта не підтверджені; купівля послуги або підписки не пропонується як уже узгоджене рішення. Відповідь про одноразовий дозвіл/експорт вирішує наступну дію; рекламне «preconditioning supported» її не замінює.

## Джерела й відтворення

- **S1.** Поточні збережені `OceanLink-Pro-1.2.6-Analysis-UA.zip`, Report PDF і `OceanLink-Pro-1.2.6.xapk` від 12.10.2026. Індекс `evidence/method-evidence.json`; SDK `com.chevalier.scusdk.sdk.ScuSdkImpl`, версія 3.6.2.3.0. SHA-256 повністю наведені в S9. Це первинний бінарний зразок та статичний аналіз, не тест авто.
- **S2.** Поточні `PKC21.3-BLE-Key-Research-UA.md` та `PKC21.3-BLE-Key-Evidence-UA.zip` від 12.10.2026; розділи 3–6, `evidence/aot-function-00a854ec.txt`, `...00a88b98.txt`, `...00a79c2c.txt`, call edges та input identities. Актуальні копії Markdown збігаються; ключа власника в них немає.
- **S3.** [Попередній CLIMATE ON](CLIMATE-ON-STAGE4-UA.md), [архітектура етапу 5](REMOTE-CLIMATE-STAGE5-UA.md); [issue #3](https://github.com/bogdanlin2/Fisker-Warm1/issues/3), [issue #4](https://github.com/bogdanlin2/Fisker-Warm1/issues/4), [PR #2](https://github.com/bogdanlin2/Fisker-Warm1/pull/2), [PR #5](https://github.com/bogdanlin2/Fisker-Warm1/pull/5). Stage 6 head перевірено `3956228f06926df85d59cc6ee4227c2ec3832cb8`; main `7069528288498c0949225d8580d0919914af985b`.
- **S4.** [OceanLink Pro FAQ](https://oceanlinkpro.com/faq), розділи Digital Key, BLE Key, PKC21, перевірено 20.10.2026. Заяви розробника, не повний контракт PKC21.3.
- **S5.** [O Loop](https://ocean.ovloop.com/), FAQ про third-party APIs, перевірено 20.10.2026. Інтеграційна політика провайдера, не вимірювання VIN.
- **S6.** [Home Assistant Pulse, актуальний commit](https://github.com/MichaelOE/homeassistant-ocean_pulse/tree/842a98ce0af959bec2a42796e59db563de8696d5), `api.py`, `entities_button.py`, README; порівняння з `ee9cac2496ac10085db99ae6214e9194c048c7f2`. Перевірено офлайн, без API-запитів.
- **S7.** [Fiskeretta](https://github.com/Fiskeretta/fiskeretta-diagnostics/tree/2254dbc4845346523da91c3a1a7b85018767de7b); [SaavyScripts](https://github.com/puddletools/SaavyScripts/tree/eaf134c680f30b04f12ffb99eacffe7531ec90d7); [CAN](https://github.com/puddletools/CAN/tree/4654bca7ecb6aa27db114ab40f63d7a1ad675660). Поточні commit SHA перевірені; незмінні ділянки повторно не сканувалися.
- **S8.** [Android BluetoothGattServer](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer), `notifyCharacteristicChanged`; [GATT server](https://developer.android.com/reference/android/bluetooth/BluetoothManager#openGattServer(android.content.Context,%20android.bluetooth.BluetoothGattServerCallback)). Загальна можливість платформи, не PKC-авторизація.
- **S9.** [Повторний статичний аудит](../verification/pkc-stage7-static-audit.json); [відтворюваний скрипт](../scripts/audit_pkc_stage7.py); [підсумок регресій](../verification/pkc-stage7-core-tests.json). `PASS_STATIC_BINDINGS_ONLY` не означає HVAC ON.
- **S10.** [Google Play: Undercurrent](https://play.google.com/store/apps/details?id=com.undercurrent.oceanlinkpro&hl=en). Функція PKC21 preconditioning та контакт розробника. Не доказ дозволеного незалежного доступу чи відповіді постачальника.

Відтворення S9: Python 3, оригінальні три збережені архіви та decode `apktool_3.0.3.jar d -r`. Версія Apktool і jar SHA-256 `dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423` звірені з [офіційним release](https://github.com/iBotPeaches/Apktool/releases/tag/v3.0.3). Запустити скрипт з `--xapk`, `--native-archive`, `--key-archive`, `--decoded-root`, `--repo`, `--output`. Сторонній APK і повні декомпільовані дерева у публічний репозиторій не додаються.

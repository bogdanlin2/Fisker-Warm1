# Fisker Warm — аудит CAN RX, етап 7.4

Дата: 20.10.2026. Авто власника: Ocean 2023 EU, OS 2.1, PKC 21.3. Телефон: S24 Ultra / Android API 36. Адаптер: vLinker FD-IOS, рядок сумісності ELM327 v2.2.

**Фізичну першопричину нульового HVAC RX ще не доведено. Звуження вже конкретне: у трьох завершених записах немає CAN-байтів ще до CAN-парсера; помилка декодування полів 534/373 не пояснює результат. Код 0.3.4 справді відправляє `ATMA\r` і очікує GATT callback, а отримання `STOPPED` узгоджується з активним моніторингом. Виявлено дефект спостережуваності: цей запис і callback не потрапляли до експорту. Підготовлено цільову 0.3.5 з повним host BLE-журналом та одним диференційним пасивним контролем.** Реальний запуск клімату застосунком не реалізовано.

## 1. Що саме перевірено

Перевірений runtime — [6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995](https://github.com/bogdanlin2/Fisker-Warm1/commit/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995), з якого Actions [39365624447](https://github.com/bogdanlin2/Fisker-Warm1/actions/runs/39365624447) зібрав встановлений 0.3.4-lab / versionCode 10. Наступний commit 83e0c57 додає лише документацію/перевірочні результати; Android-код, build scripts і workflows відносно runtime не відрізняються. У PR #9 змін не внесено; main не змінено.

Основні вимірювання — три JSON власника `Fisker-Warm-HVAC-2026-10-20T06-32-48.894443Z.json`, `...06-35-24.253964Z.json`, `...06-37-27.581858Z.json`. Це накопичувальні експорти з 2, 4, 5 записами; після дедуплікації за початком сеансу маємо **3 captures і 2 preflights**, а не дев'ять окремих тестів. SHA-256 оригіналів, відповіді всіх команд, часові відстані й позначки збережені в [похідному зведенні](../verification/stage7-4/measured-summary.json). MAC та повний GATT-вміст у публічне зведення не включені.

| Реальний capture, UTC | ID | CAN / notifications / bytes | Єдине сире RX | Час RX від початку моніторингу | Позначки, секунди від початку |
|---|---:|---:|---|---:|---|
| 06:30:42.815 | 534 | 0 / 1 / 10 | `53544F505045440D0D3E` | 30.042414 с | немає |
| 06:34:47.495 | 534 | 0 / 1 / 10 | той самий `STOPPED\r\r>` | 30.032583 с | HMI_ON 8.458928; HMI_ON 13.029172 |
| 06:36:51.427 | 373 | 0 / 1 / 10 | той самий `STOPPED\r\r>` | 30.043430 с | HMI_ON 6.022283; AIRFLOW_PRESENT 15.102693; HMI_OFF 28.735386 |

Усі три: WINDOW_ELAPSED, повні 30000/30001 мс, stopWriteConfirmed/stopConfirmed/promptSeen true, invalidFrameLines=0, truncated=false, BUFFER FULL=false, containsSyntheticInput=false. Напруга адаптера 14.1 V під час captures. Ручні позначки є свідченням дій/спостережень власника; вони не є кадрами CAN або виміряним ECU-статусом. Реальна робота штатного клімату не доводить його трансляцію в OBD.

## 2. Повний шлях виконання встановленої 0.3.4

Наведені посилання й номери стосуються саме встановленого runtime, а не нового коду.

| Етап | Конкретний код 0.3.4 | Результат аудиту |
|---|---|---|
| Натискання / новий сеанс | [MainActivity.confirm/start](https://github.com/bogdanlin2/Fisker-Warm1/blob/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995/app/src/main/java/com/fiskerwarm/lab/MainActivity.java#L120-L185) | Кожен capture створює новий BleProbe. Попередній preflight скидає адаптер та закриває своє з'єднання. |
| GATT і notifications | [BleProbe callback/subscribe](https://github.com/bogdanlin2/Fisker-Warm1/blob/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995/app/src/main/java/com/fiskerwarm/lab/BleProbe.java#L130-L247) | Відомий service 18F0, TX 2AF1, RX 2AF0; перевірка UUID та instance IDs. MTU завершено до CCC; worker починається тільки після успішного descriptor callback. В усіх captures MTU=247, CCC=0. |
| Фактичний write | [BleProbe.write](https://github.com/bogdanlin2/Fisker-Warm1/blob/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995/app/src/main/java/com/fiskerwarm/lab/BleProbe.java#L249-L264) | API 33+ writeCharacteristic зі snapshot byte[]. Properties 2AF1=12, отже обрано WRITE_TYPE_DEFAULT, з відповіддю. Queue rejection або callback без GATT_SUCCESS протягом 3 с породжує помилку. |
| Ініціалізація / фільтр | [BleProbe.read/hvacCapture](https://github.com/bogdanlin2/Fisker-Warm1/blob/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995/app/src/main/java/com/fiskerwarm/lab/BleProbe.java#L276-L355) | ATZ → ATE0 → ATI → ATRV → ATL0 → ATS1 → ATH1 → SP6 → CAF0 → CFC0 → CSM1 → D1 → DPN=6 → AR → CM7FF → CF534/373. Кожна команда чекає prompt. Це збігається з реальними журналами. |
| Старт ATMA | [ElmChannel.monitor](https://github.com/bogdanlin2/Fisker-Warm1/blob/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995/app/src/main/java/com/fiskerwarm/lab/core/ElmChannel.java#L124-L195) | Рядок 141: writer.write точних ASCII `ATMA\r` (hex `41544D410D`). Stream і monitoring вмикаються **до** write. Метод не переходить до очікування вікна, поки write не завершився. |
| Відсутність переривання | той самий monitor + sendLock у listenCanSnapshot | Замок sendLock утримується весь monitor і stop drain. Інші AT не можуть вклинитися. Позначки/оновлення UI не пишуть в GATT. Таймер сам по собі не є підтвердженням ATMA; нормальний шлях результату також вимагає повернення write. |
| RX до парсера | [BleProbe.onCharacteristicChanged](https://github.com/bogdanlin2/Fisker-Warm1/blob/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995/app/src/main/java/com/fiskerwarm/lab/BleProbe.java#L220-L227), [ElmChannel.receive](https://github.com/bogdanlin2/Fisker-Warm1/blob/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995/app/src/main/java/com/fiskerwarm/lab/core/ElmChannel.java#L224-L259) | API 36 використовує overload з byte[], дублювання через deprecated callback немає. Обробка під receiveLock. Відкинуті до цього місця callbacks у 0.3.4 окремо не журналювалися. |
| Сирі пакети / парсинг | [CanCapture.Stream.accept](https://github.com/bogdanlin2/Fisker-Warm1/blob/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995/app/src/main/java/com/fiskerwarm/lab/core/CanCapture.java#L154-L215) | Повні bytes/час notification додаються до packets **перед** розбором байтів, ID і DLC. Неуспішний парсинг залишив би сирий hex, зростання notifications/bytes та invalid/partial. Цього немає. |
| Зупинка / очищення | [ElmChannel.monitor](https://github.com/bogdanlin2/Fisker-Warm1/blob/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995/app/src/main/java/com/fiskerwarm/lab/core/ElmChannel.java#L156-L189), [BleProbe.resetAdapter/finish](https://github.com/bogdanlin2/Fisker-Warm1/blob/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995/app/src/main/java/com/fiskerwarm/lab/BleProbe.java#L355-L360) | Один SPACE, до 1 с drain prompt, потім ATZ і close GATT. Порожнього CR, що повторює попередню команду, немає. ATZ не очищує збережений snapshot. STOPPED приходить через 33–43 мс після завершення вікна, ATZ — пізніше. |

### Що саме гарантує успішний шлях ATMA

Код вимагав прийняття write API та успішного callback для вибраної характеристики. За коректної поведінки Android stack результат NO_MATCHING_FRAMES несумісний із невиконаним/простроченим write. Після ATZ, підтверджених prompt усіх налаштувань та відсутності інших monitor-команд `STOPPED` у відповідь на SPACE — додатковий, сильний доказ переходу адаптера в monitor. Це висновок із коду та відповіді, а не захоплення CAN-трансивера.

**Прогалина 0.3.4:** ATMA відправлявся прямо з monitor, оминаючи BleProbe.command/events. startWriteMillis обчислювався (ElmChannel, рядок 175), але [HvacJournal](https://github.com/bogdanlin2/Fisker-Warm1/blob/6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995/app/src/main/java/com/fiskerwarm/lab/core/HvacJournal.java#L29-L54) його не експортував. Сирий журнал охоплював тільки прийняті monitor notifications, а не всі TX/RX, queue/callback і відхилені endpoint-и. Це дефект доказовості, **не встановлена причина фізичного нульового RX**.

## 3. AT-команди та фільтри

Первинне джерело — [ELM327 v2.2 datasheet ELM327DSK](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf), друковані сторінки 13–17, 21, 47–49 (PDF indices на одну менші). За ним:

- CM позначає одиницями біти ID, що мають збігатися; CF задає їх значення. Для 11-бітного ID mask 7FF = одинадцять одиниць. Критерій `((id XOR filter) AND 0x7FF)==0` приймає рівно filter. Автоматизовано перебрані всі 2048 ID для 534, 373 і контрольного 0E9. Зсуву CAN-ID, ISO-TP байта або DLC до mask/filter додавати не потрібно.
- ATAR скидає попередні receive settings **до** CM/CF. Після CF у досліджених сеансах немає CRA/AR/SP/ATZ до завершення monitor. Давній ATCRA7F8 не пояснює ці записи. Порядок CM→CF припустимий; ELM також наводить CF→CM, коли жодного monitor між ними немає. Наш канал додатково вимагає ACK CM перед CF.
- MA враховує CM/CF/CRA. Внутрішній Protocol Close на старті MA не є документованим скиданням цих фільтрів. CRAxxx — альтернативне налаштування того самого точного приймального критерію, а не інша фізична шина.
- CAF0 вимикає автоматичне ISO-TP форматування; CFC0 забороняє автоматичний flow control; ATH1/ATS1/D1 керують відображенням заголовка, пробілів і DLC. Непідтримуваний текстовий формат дав би raw bytes та помилку парсингу, а не один STOPPED-пакет.
- CSM1 задає silent monitor; CSM0 міг би генерувати ACK/error bits і **не використовується**. У моніторингу ELM не має посилати flow control. Жодного OBD payload немає. DPN=6 підтверджує налаштований стандартний 11-bit / 500 kbit/s протокол, але не вимірює бітову швидкість фізичної HVAC-мережі.
- SPACE припиняє monitor; потрібно дочекатися `>`. Зупинний символ відкидається адаптером. ATZ після prompt скидає лише адаптер, не ECU автомобіля.

**Межа щодо конкретного Vgate.** [Офіційна інструкція Vgate для FD-IOS BLE](https://forum.vgatemall.com/showthread.php?pid=365&tid=250) підтверджує саме BLE ім'я й вибір ELM-інтерфейсу. [Download center](https://www.vgatemall.com/downloadcenter/) містить окрему FD firmware v2.2.92; ATI=`ELM327 v2.2` не визначає встановлений номер прошивки Vgate. Публічного незалежного опису реалізації CSM/CM/CF саме цього екземпляра не встановлено. ACK CSM1 не є електричною перевіркою silent mode; ACK CF не доводить критерій приймання. Не робимо також висновку, що фільтрація обов'язково апаратна: ELM описує відбір повідомлень адаптером, а реалізація Vgate закрита. Прошивку не оновлюємо.

Android-контракт прийняття запиту та окремого completion callback перевірений у [BluetoothGatt.writeCharacteristic API 33+](https://developer.android.com/reference/android/bluetooth/BluetoothGatt#writeCharacteristic(android.bluetooth.BluetoothGattCharacteristic,%20byte[],%20int)). Його success не тотожний CAN RX.

## 4. Що відомо про OBD і мережі

Звіт етапу 3 і оригінальний експорт 15.10.2026 дають **41 текстовий кадр 0E9 через той самий UART 18F0/2AF1/2AF0**, потім BUFFER FULL приблизно за 147 мс. Тоді CSM1, D1, DPN=6 також були прийняті. Це доводить попереднє приймання адаптерного CAN-потоку через OBD. Це не одночасний контроль 20 жовтня, не тест нового APK і не доказ фізичного підключення OBD до всіх мереж. Сам факт раніше прочитаної ECC-ідентичності за 7F0/7F8 доводить маршрут діагностичного запиту/відповіді, а не прозору трансляцію циклічних ECC повідомлень.

[Використаний у 7.2 відкритий DBC](https://github.com/moistpope/oceanfront-view/blob/68a88af90b369c1b2c55d7949c4c848f41deb857/dbc/fisker_ocean_ibus.dbc) є похідним від FM29 V390.1 / 20230120, а не перевіреною для VIN/OS власника OEM схемою. Точні місця:

| ID | DBC рядки | Заявлена мережа / відправник / цикл | Що випливає |
|---|---|---|---|
| 534 | BO_ 1244; сигнал 1253; CM_ 4107 | IBUS1, ICC; NoMsgSendType | Це команда HMI у матриці; в OBD вона ще не спостерігалася. |
| 373 | BO_ 759–779; CM_ 3695; cycle 5691 | IBUS1, GW; 100 мс | Це ECC-статуси, які за матрицею передає GW на IBUS1. Сам номер 373 не доводить пряме підключення ECC до OBD. |
| 0E9 | BO_ 1496; CM_ 4317; cycle 5753 | IBUS2, GW; 20 мс | Це зручний історично отриманий контроль. Збіг ID не є pinout-доказом, що OBD фізично дорівнює IBUS2. |

[CanBridge.kt](https://github.com/moistpope/oceanfront-view/blob/68a88af90b369c1b2c55d7949c4c848f41deb857/android/app/src/main/java/ai/sunnypilot/webhud/CanBridge.kt) і [SocketCAN helper](https://github.com/moistpope/oceanfront-view/blob/68a88af90b369c1b2c55d7949c4c848f41deb857/android/canbridge/main.go) працюють з окремими can1/IBUS1 та can2/IBUS2 контролерами Pulse. Вони не доводять рівність цього маршруту стандартному Vgate/OBD. Підтвердженої OEM схеми контактів конкретного EU автомобіля й правил broadcast forwarding не отримано. Заява «534/373 точно на іншій фізичній шині і ніколи не доступні через OBD» була б передчасною. Відомості про TBOX також не доводять доступність потрібних broadcast кадрів на OBD.

### Альтернативи після аудиту, у порядку пріоритету

| Причина | За | Проти / межа | Що її розрізнить |
|---|---|---|---|
| Вибіркова видимість OBD / gateway або несумісність матриці з OS/VIN | Немає raw HVAC bytes під час реальної роботи; матриця розрізняє IBUS1/2; раніше був лише 0E9. | Немає поточного позитивного CAN контролю й OEM pinout. Фільтр може мати особливості. | 0E9 до/після + 373 двома точними API в одному сеансі. Навіть за позитивного контролю це доведе лише недоступність **дослідженого ID у цих умовах**, а не всіх CAN-шляхів. |
| Особливість CM/CF/CSM/protocol реалізації Vgate або поточного фізичного OBD стану | OK не перевіряє функцію; версія Vgate не ідентифікована. | Раніше той самий UART/SP6/CSM1 приймав 0E9; помилок адаптер не повідомив. | Точний 0E9 позитивний контроль, еквівалент CRA і повтор CM/CF за появи RX. Обидва API можуть мати спільне обмеження; їхній нуль сам по собі не доводить тишу фізичної шини. |
| Помилка ATMA/GATT або відсікання RX до stream | У 0.3.4 немає безпосереднього журналу write/callback і відхилених callbacks. | Послідовний код чекає completion; усі replies і STOPPED приймаються на 2AF0; немає винятку/timeout. Офлайн позитивні та негативні сценарії проходять. | Новий callback-boundary raw журнал і позитивний контроль тієї самої збірки. Повністю виключити поведінку Android radio stack офлайн неможливо. |
| Виключно декодер, DLC або видалення frames після stop | Можливі в загальному випадку. | У всіх captures raw містить лише STOPPED; немає invalid/partial; snapshot зберігається після ATZ. | У межах отриманих журналів ця версія **не пояснює результат**. Синтетична перевірка розбиття пакетів і експорту проходить. |

29-бітний протокол не обрано навмання: досліджені матричні ID — стандартні 11-бітні. Зміна SP6 на SP7 не дасть доступу до іншої фізичної шини й прибрала б єдиний раніше виміряний контроль. Потреба в іншому інтерфейсі/обладнанні ще не доведена.

## 5. Мінімальне цільове оновлення 0.3.5

Підстава нового APK — описаний дефект доказовості та відсутній позитивний контроль. Це **не заявлене виправлення знайденої причини фізичного нульового RX**.

- [GattWriteGate](../app/src/main/java/com/fiskerwarm/lab/core/GattWriteGate.java) виділяє існуючу single-flight схему для тестування й журналювання: TX_REQUEST, точні bytes, TX_QUEUE_RESULT, TX_CALLBACK, TX_COMPLETE або TX_FAILED; окремий id операції. Timeout/помилка — terminal, без повтору. Сторонні/закриті GATT callback не завершують очікування.
- [BleIoJournal](../app/src/main/java/com/fiskerwarm/lab/core/BleIoJournal.java) записує **всі notification callbacks до endpoint/state routing**, усі adapter TX, queue/completion/failure, повний hex і System.nanoTime. Сирий журнал не залежить від CAN-парсера. Межі 8192 подій / 600 kB bytes, явні droppedEvents; заповнення припиняє вимірювання. Це host-події, не on-wire capture. Події до Android callback спостерігати не можемо.
- HvacJournal тепер експортує startWriteMillis/startWriteConfirmed, значення фізично не підміняє припущенням. У UI `ATMA: GATT_SUCCESS · це не CAN RX` відрізняється від лічильника реально прийнятих кадрів.
- [CanRxAudit](../app/src/main/java/com/fiskerwarm/lab/core/CanRxAudit.java) виконує один адаптивний експеримент: точний 0E9 до 1 с/3 кадрів; за наявності RX — точний 373 3 с; за нуля 373 — точний CRA373 3 с, за RX повтор CM/CF373 3 с; потім точний 0E9 до 1 с/3 кадрів. Якщо початковий 0E9 порожній, замість HVAC-припущень виконується CRA0E9 до 1 с/3 кадрів і, за RX, повтор CM/CF0E9. Максимум 11 с запланованого моніторингу; setup/stop drain додають час. Поріг ≥3 ініціює stop, але не обрізає notification: кадри того самого пакета та stop drain зберігаються, тому фактичний count може перевищити 3. Усі фази — одне GATT-з'єднання без ATZ між ними.
- При BUFFER FULL, неправильному ID, malformed payload, BLE disconnect, timeout або ручній зупинці наступних фаз немає. Отриманий prefix зберігається. Контрольний frame limit — окрема запланована причина завершення, не втрата/переповнення. Навіть якщо переповнення таки станеться, журнал не назве вимірювання повним.
- [PassiveNetworkPlan](../app/src/main/java/com/fiskerwarm/lab/core/PassiveNetworkPlan.java) і перевірка безпосередньо перед write допускають тільки фіксовані adapter AT, MA та один stop SPACE. 0E9/CRA дозволені лише окремій capability аудиту; звичайний HVAC-канал не розширено. Жодних довільних ID, broad mask, CSM0, ECU/UDS payload, wake-up чи PKC enrollment.
- Добре видимий cyan checkbox на контрастному тлі підтверджує стоянку P/ключ/власника поруч. Автоматичного reconnect/продовження після помилки немає. Старі 30-секундні тести залишені, але для етапу 7.4 повторювати їх не потрібно.

ReadOnlyPolicy і HeatSafety побайтно незмінні; це також перевіряється тестом із pinned SHA-256. Source Manifest зберігає com.fiskerwarm.lab03; CI профіль lab02 формує сумісне оновлення встановленого com.fiskerwarm.lab02 з оригінальним сертифікатом. Версія збільшена до 11 / 0.3.5-lab.

## 6. Автоматизовані перевірки та їхня межа

Baseline 0.3.4 запущено без змін: усі чинні перевірки пройдені; [результати](../verification/stage7-4/baseline-test-summary.json). Нові [CanRxChecks](../tests/CanRxChecks.java) використовують fake async GATT/ELM з окремим callback executor. Перевіряють точні ATMA bytes, порядок команд, відсутність перекриття GATT, RX до completion, queue rejection, missing/error/late callback, disconnect/terminal gate, SPACE і prompt, експорт raw та план frame limit. Додані всі основні позитивні/нульові/CRA-only/overflow гілки контрольного експерименту. Існуючі тести охоплюють всі BLE split points, malformed/missing DLC, CR/LF/NULL/`>`, BUFFER FULL без prompt, stop timeout, user stop, збереження prefix і повне 30-секундне синтетичне вікно.

[Незалежні Python-перевірки](../tests/test_can_rx_export.py) читають Java JSON зовнішнім parser-ом: повні TX bytes, callback і відхилене RX збережені, control 0E9 не декодується як HVAC; provenance SYNTHETIC_TEST не перетворюється на фізичний RX. Окремо перевіряється похідне реальне зведення: три різні captures, рівно 10 STOPPED bytes у кожному, правильні часові межі/позначки та відсутні старі поля TX.

**Офлайн-відтворення старих bytes є SYNTHETIC_TEST, навіть коли самі bytes походять зі справжнього експорту. Це не новий тест авто.** Новий APK не перевірено на фізичному S24/Vgate/Ocean; його build/signature/identity та CI перевіряються окремо. Емулятор Android і фізичний CAN-тестер тут недоступні; поведінку реального RF, втрати цілих notifications до Android callback і електричну тишу програмні тести не доводять. **Готовий APK фактично завантажений і звірений:** [APK CI 39384997170](https://github.com/bogdanlin2/Fisker-Warm1/actions/runs/39384997170) і [CAN CI 39384997135](https://github.com/bogdanlin2/Fisker-Warm1/actions/runs/39384997135) успішні на runtime 859ff6662af3c4ce76f3c2f96ce55acec5c28fac. Усі 305 перевірок пройдені (27 нових). Посилання на прямий APK-артефакт, перевірку сумісності 0.3.4→0.3.5, SHA й коротку процедуру наведено в [DELIVERY-UA.md](../verification/stage7-4/DELIVERY-UA.md). Наступний commit додає тільки документацію та CI evidence; нового APK для нього не потрібно.

## 7. Одна наступна практична дія

1. Встановіть **Fisker-Warm-CAN-RX-0.3.5.apk** як оновлення поточного застосунку; стару версію видаляти не потрібно. Відкрийте застосунок; архів старих вимірювань залишається архівом, а не живим RX. Якщо журнал заповнений — спершу експортуйте, потім «Новий журнал».
2. Авто стоїть у P зі стоянковим гальмом, пробуджене штатним способом, власник поруч зі штатним ключем; немає оновлення ПЗ або попереджень живлення. Інші OBD-застосунки закриті. Не залишайте адаптер/сеанс працювати у сплячому авто. Позначте добре видимий checkbox умов.
3. «Підключити Vgate» → вибрати свій FD-IOS → дочекатися підтверджених **налаштувань**, які ще не означають CAN RX.
4. Один раз натисніть **«Контроль CAN RX · 0E9 → 373 → 0E9»**, прочитайте й підтвердіть пасивний тест. Не натискайте старі 30-секундні кнопки, не змінюйте HVAC під час контролю. Чекайте завершення; параметри клімату залишаються штатними.
5. «Експортувати журнал» → збережіть JSON. Після завершення від'єднайте Vgate. Якщо потрібно перервати — «Зупинити»; якщо reset/prompt не підтверджено, BLE зірвався чи є попередження авто — фізично від'єднайте Vgate. Жодних reset ECU або прихованого retry.

Точні команди всіх фаз видно в коді CanRxAudit: перед кожним MA — `ATAR`, потім `ATCM7FF` / `ATCF0E9|373` або альтернативне `ATCRA0E9|373`. Очікується OK; SP6/DPN=6/CSM1 та решта startup налаштувань обов'язкові. MA не має звичайної відповіді OK; потрібні окремі queue/callback TX свідчення. Завершення — одиничний hex `20`, відповідь `STOPPED\r...>` (або вже отриманий prompt), після фінальної фази — `ATZ` і ELM banner. Жодного ECU payload немає. Можливі побічні ефекти: адаптер/телефон споживають живлення, автомобіль може довше лишатися пробудженим, firmware реалізація silent не перевірена електрично. Тест короткий, при будь-якій аномалії припиняється.

| Отриманий результат | Практичний висновок / подальша дія |
|---|---|
| Контроль 0E9 є до/після; 373 є | CAN RX і поточний шлях до цього ECC ID підтверджені. Аналізуємо **свіжі** payload/статуси; передавання команд це не дозволяє. |
| 0E9 є до/після; 373 немає через CM/CF і CRA | Загальна непрацездатність monitor/RX значно відсіяна. У цих умовах ID 373 не спостерігається на доступному OBD маршруті. Наступне дослідження — підтверджена схема/forwarding та відповідність ID OS/VIN; не ще одне довше те саме захоплення й не купівля навмання. |
| Той самий ID видно лише через CRA, між двома нульовими CM/CF | Є конкретна відтворена різниця фільтрувального API. Спочатку аналізуємо timings/raw та виправляємо вибір фільтра; фізичну мережу не оголошуємо недоступною. |
| 0E9 немає через обидва точні API | Немає чинного позитивного контролю. Висновки про недоступність HVAC робити не можна. Raw TX/callback/endpoint і stop визначать, на якому програмному рівні зупиняти пошук; за коректного host-тракту залишаються firmware/поточний фізичний стан OBD, які цей інструмент без іншого вимірювання не розділить. |
| Timeout, overflow, інший ID, втрачений post-control | Неповний/неоднозначний результат. Зберегти prefix, припинити, не переходити до CAN TX. |

**Критичний наступний крок — саме один цей контрольний сеанс на тому самому S24/Vgate, а не повтор 534/373 HMI ON/OFF.** Він додає відсутній одночасний позитивний контроль, безпосередній ATMA TX/callback і альтернативне точне налаштування фільтра. Жоден його результат сам по собі не означає, що Fisker Warm уже може ввімкнути штатний клімат.

# Fisker Warm: CAN BUFFER FULL — етап 2

Дата: 14.10.2026. Автомобіль: Fisker Ocean 2023 EU, Ocean OS 2.1; версії окремих модулів можуть відрізнятися. Телефон: Samsung S24 Ultra / Android 16. Транспорт: vLinker FD-IOS BLE, банер ELM327 v2.2. База аудиту: коміт `0d2996014433326e27bc20ef93b0ddbc6af8f0f4`, діагностична гілка; `main` не змінюється.

## 1. Технічний висновок і конкретне рішення

**Доведено:** монітор завершує сам адаптер зі статусом BUFFER FULL і prompt. Це відбувається до 500 мс, без досягнення ліміту зберігання застосунку. Новий журнал підтверджує справне отримання BLE-пакетів і завершення моніторингу; він не визначає конкретний внутрішній буфер.

**Найімовірніший механізм, середня впевненість:** потік 0E9 перевантажує тракт виведення CAN → ASCII → внутрішній UART/черги → Bluetooth. Сильний незалежний збіг: у первинному коді Fiskeretta `no_filter_sweep()` автор прямо описує потік 0E9 на Ocean, який при відкритому acceptance filter заважає діагностиці, і відсікає його маскою 700/700. Назва «gateway heartbeat» — атрибуція автора, а не підтверджене декодування нашого кадру [4]. Це підтримує механізм домінантного потоку, але не доводить, що вузьке місце саме BLE, UART або певний FIFO.

**Реалізоване рішення:** нова кнопка «3. Перевірити інші CAN ID без 0E9». Вона виконує коротку відкриту контрольну пробу та 11 послідовних пасивних проб із приймальними масками. Усі 2047 інші 11-бітні ID охоплено рівно один раз; 0E9 відсікається до ASCII-виведення, якщо firmware справді реалізує підтверджені маски. Це перевіряє доступність іншого трафіку, а не фільтрує лише 0E9 всередину.

Кожна проба має незмінне вікно 500 мс і попередній ліміт 1500 рядків. Сумарні 11 вікон потрібні для покриття ID; вони не продовжують одну переповнену пробу. BUFFER FULL завжди залишається ознакою INCOMPLETE. Це технічна підстава для нового діагностичного APK з тим самим підписом: додано потрібний спосіб перевірки гіпотез, не косметичне підвищення номера. Усунення BUFFER FULL на автомобілі поки не заявляється.

## 2. Що встановлено з контрольного журналу

Файл: `fisker-warm-lab-2026-10-14T15-00-22.226020Z.json`; SHA-256 `c5403a992d8429c9771da8697eaf0e92188e0c36a91ec6f2474a6a6d9d5656c4`. Перевірено фактичний JSON, а не лише таблицю з завдання. Узагальнені дані без адреси пристрою збережено у `verification/can-stage2-evidence.json`.

| Показник | OFF | ON | Що означає |
|---|---:|---:|---|
| Валідних кадрів / ID | 44 / 0E9 | 45 / 0E9 | Обидва потоки домінуються одним ID |
| Отримано байтів / notifications | 1311 / 7 | 1313 / 7 | Шість пакетів по 200 байтів і один 111/113; порядок не записаний |
| MTU / найбільший пакет | 247 / 200 | 247 / 200 | Немає обмеження до 20 байтів; запас MTU не змушує firmware посилати більші пакети |
| Перше / останнє notification, мс | 50 / 156 | 49 / 165 | Час телефону, не час фізичного CAN |
| Найбільший інтервал notification, мс | 29 | 30 | Connection interval і кількість радіопакетів не виміряні |
| BUFFER FULL / завершення, мс | 156 / 158 | 165 / 167 | Зупинка адаптера до 500-мс дедлайну |
| Початковий запис ATMA, мс | 29 | 30 | Завершення host GATT write, не час виконання ECU |
| stopConfirmed / stopSent | true / false | true / false | Prompt отримано; зайвий CR не надсилався |
| Невалідних рядків / app truncation | 1 / false | 0 / false | Втрата/дефект одного OFF-рядка не локалізовані |
| Повне 500-мс вікно | ні | ні | Обидві проби непридатні як повне порівняння HVAC |

У OFF збережені повні зразки `48 F2 FF FF 00 1C 8F C0`; у ON — `10 F3 FF FF 00 1A 8F C0` та шестибайтний префікс `10 F3 FF FF 00 1A`. Попередній формат без явного DLC і raw trace не дозволяє відрізнити фізичний DLC=6 від укороченого виведення адаптера. Різниця payload не доводить роботу клімату.

Приблизно 8,4/8,0 кБ/с за повним host-вікном або 12,4/11,3 кБ/с між першим і останнім notification — лише темп отриманого ASCII. Перший пакет уже містить накопичені дані; ці оцінки не є UART baudrate, BLE air rate чи CAN bitrate. Числа 44–45 і 1311–1313 не визначають розмір FIFO: невідомі одночасний запис/читання черг, формат, службові байти та втрати.

## 3. Аудит коду та властивості адаптера

`BleProbe` правильно розділяє worker із GATT write/wait та receive callbacks. На Android 16 використовується callback із переданим byte[]; старий callback виконує приймання лише до API 33, тож дублювання пакета немає. Запит MTU завершується до CCC subscription, далі операції серіалізовані. HIGH priority — лише запит; його прийняття не доведене. Клонування одного пакета до 200 байтів захищає його життєвий цикл; у callback немає файлового I/O, UI-log на кожен пакет чи sleep.

`ElmChannel.receive()` синхронно передає фрагменти bounded parser під коротким receiveLock. Очікування відповіді/монітору віддає цей lock через timedWait; GATT write не тримає його. Зберігання повного raw-потоку в звичайному snapshot вимкнене. `CanCapture` зберігає лише лічильники, ID та до 12 payload-зразків на ID. Пострічкове split/regex створює невеликі об'єкти, але в коді немає накопичувального копіювання всього CAN-потоку або ліміту 45 кадрів.

Фрагментовані кадри, BUFFER FULL і prompt збираються інкрементально. Монітор не поновлюється автоматично після переповнення; дочитується prompt. При відсутності prompt канал закривається; нові команди не йдуть. Виправлення цих помилок із етапу 1 збережено. Додаткової доведеної помилки, що пояснює поточне переповнення, не знайдено. Одна невалідна OFF-лінія потребує raw-підтвердження, а не пом'якшення parser.

Синтетична офлайн-проба: 1313 байтів у 7 пакетах, 45 валідних кадрів; 2000 повторів після прогріву. Медіана parser 114 мкс, p95 366 мкс, максимум 8323 мкс на desktop JDK 17. Це не raw-відтворення журналу і не замір Android. Воно відхиляє ідею про вбудований 45-кадровий stop та показує порядок вартості parser, але не виключає Android scheduler/GC або затримку до callback. Новий режим записує час parser і очікування receiveLock, окремо від часу приходу пакета.

ATSP6 означає 11-бітний ISO15765 CAN на 500 кбіт/с; ATCAF0 вимикає автоматичне CAN-форматування, ATCFC0 — автоматичний flow control. ATMA залежить від acceptance filter. Звичайний application parser не фільтрує тільки 0E9; попередній ATZ і OK від ATSP6 не є доказом фактичного стану vendor-фільтрів. Новий режим явно задає masks/filters [1].

Офіційний FD+ підтримує AT/ST, ISO15765, raw CAN і MS-CAN [2]. Legacy-опис виробника рекламує USART buffer до 2 кБ і 230,4 кбіт/с [3]. Це не специфікація CAN FIFO, не фактичний baudrate нашого BLE-з'єднання і не доказ, що модель/firmware власника тотожні рекламованій. Manufacturer datasheet описує 512-байтовий RS232 transmit buffer genuine ELM; це вихідний serial buffer, а не розмір CAN FIFO Vgate. Переносити цей розмір на Vgate не можна. Банер v2.2 підтверджує заявлену сумісність, а не походження чипа чи повноту команд. Доступний manufacturer datasheet — ELM327DSJ; його не видаємо за firmware specification саме цього Vgate v2.2 [1].

200 байтів коректно вміщуються в MTU 247 (ATT payload до 244). BLE UART є мостом без CAN timestamps; збільшення MTU не збільшує автоматично периферійний UART FIFO. Виробник описує Android Classic-підключення FD-Android, а BLE для iOS [2]; це не спростовує вже працюючий BLE на Android. Перехід на SPP дозволив би змінити одну ланку експерименту, але зберіг би ELM/UART і ту саму OBD-видимість. Документація Linux can327 прямо обмежує очікування від ELM як CAN-інтерфейсу та описує відмінності реалізацій [5]. AndrOBD підтверджує практику Android OBD через різні транспорти, але діагностичне polling не доводить повноцінний passive sniffer [10].

## 4. Таблиця гіпотез

### A. Перевіряється без автомобіля

| Гіпотеза / стан | Докази за та проти | Ще потрібні дані | Як перевірено / перевірити | Зміна коду | Інше обладнання | Ризик |
|---|---|---|---|---|---|---|
| Ліміт застосунку зупиняє на 45. Відхилено | MAX_CAPTURE_LINES=1500; truncation=false; емітований BUFFER FULL | Немає для відхилення цієї причини | Source audit, понад 45 кадрів у regression; bounded trace не змінює frameCount | Ні, ліміти збережено | Ні | Хибно маскувати проблему збільшенням ліміту |
| Втрата фрагментів/помилка stop спричиняє нинішній early stop. Не підтримано | 7 пакетів прийнято, prompt/stopConfirmed=true; OFF має один invalid | Raw OFF-рядок | Fragment/DLC/overflow/prompt/late-tail regressions; додано raw trace | Лише спостереження | Ні | Ослаблений parser міг би зарахувати обрізаний кадр |
| Callback блокується на I/O/очікуванні. У коді не знайдено | Немає I/O/sleep; timedWait віддає lock; desktop parser швидкий | Фактичний Android час до/в callback | Audit виконано; нові parser/lock timings виміряють тільки нашу частину callback | Додано вимірювання | Ні | Desktop benchmark не переноситься на S24 як доказ |
| Маски неправильно покривають інші ID. Офлайн відхилено | Вичерпний перебір 000–7FF: 0E9 має 0 збігів, кожен інший рівно 1 | Реалізація mask у firmware | 2048-ID exhaustive check, bounds/immutable plan; hardware OK недостатньо | Додано фіксований план і mismatch stop | Ні | Емулятор не доводить hardware filter |

### B. Один короткий пасивний тест на автомобілі

| Гіпотеза / стан | Докази за та проти | Ще потрібні дані | Як перевірити | Зміна коду | Інше обладнання | Ризик |
| Домінантний 0E9 переповнює output queue. Найімовірніша | В обох пробах тільки 0E9; незалежний Fiskeretta описує такий потік. Швидкість фізичного потоку невідома | Інші ID після exclusion; overflow і timings кожної групи | Нова автоматична кнопка: reference + 11 disjoint ranges | Реалізовано receive-filter audit | Ні | Відсутність overflow після фільтра не доводить весь HVAC-протокол |
| BLE — вузьке місце. Відкрита | Пакети по 200, інтервали до 30 мс можуть обмежувати output; MTU 247 і справний prompt проти версії «немає приймання» | Negotiated interval/PHY, radio trace; Android queue time | Поточний тест зіставить parser/lock та filtered streams; остаточно BLE не ізолює | Лише вимірювання | Для остаточного розділення SPP/USB або BLE trace | Не оголошувати BLE винним лише за темпом ASCII |
| UART/FIFO/firmware має фіксовану межу. Відкрита | Майже однаковий byte count/end; manufacturer описує буфер. Тільки 2 проби, черги дренуються | Raw хвіст, DLC, ATCS, vendor internals або незалежний транспорт | Trace/status у тому самому тесті; 44–45 не використовувати як розмір буфера | Реалізовано bounded trace, ATD1, raw ATCS | Не для першого тесту; можливо пізніше | Firmware update/baud зміни без доказу заборонено в цьому рішенні |
| Успадкований або некоректний acceptance filter ховає IDs. Відкрита | Старий hardware state не верифікований; parser приймає всі ID | Поведінка відкритої та кожної заданої маски | Вимагати OK, перевірити кожен повернений ID; mismatch завершує аудит | Реалізовано | Ні спочатку | OK без відповідного результату не підтверджує filter |

### C. Потрібен фізичний CAN-доступ або інший інтерфейс для остаточного доказу

| Гіпотеза / стан | Докази за та проти | Ще потрібні дані | Як перевірити | Зміна коду | Інше обладнання | Ризик |
| Немає ACK на діагностичній лінії: повтори одного кадру. Відкрита | Однакові OFF payload, silent monitor, можливий dedicated port. Автор Fiskeretta називає легітимний heartbeat; періоди CAN невідомі | Error frames, retry intervals, transceiver counters/wiring | Позичений native CAN із підтвердженим listen-only + error/timestamps; ATCS=0 не виключає проблему іншого передавача | ACK увімкнення не реалізовано | Може знадобитися | CSM0/ACK у невідомій мережі може втрутитися в CAN; не виконувати |
| OBD gateway не віддає HVAC broadcasts. Відкрита | В пасивних пробах лише 0E9; ECC UDS identity історично доступна. UDS маршрут не дорівнює broadcast bridge | Чисті filtered windows, документована routing/wiring | Спершу аудит; якщо всі порожні — перевіряти topology/authorized route, не повторювати broad ATMA | Перший відсів реалізовано | Лише після підтвердження потреби | Порожні 500 мс не доводять абсолютну відсутність ID |
| Потрібна інша фізична мережа / 29-bit / інший bitrate. Відкрита | ATSP6 перевіряє лише один формат; MS-CAN у Vgate не означає Fisker pinout | Офіційний pinout, bitrate, format потрібної мережі | Документація topology або qualified listen-only capture на підтверджених проводах; без auto protocol search | У новому режимі SP7/інші baud не додані | Можливо multi-CAN/native interface | Невірні pins/termination/ACK можуть пошкодити або порушити мережу |
| Для обігріву потрібен авторизований PKC/gateway протокол. Не підтверджено для нашого APK | OceanLink описує credentials і preconditioning; відкриті DBC дають identity, не HVAC start | Owner-authorized provisioning, start/stop/wake, telemetry і vehicle timeout для точних модулів | Публічна/дозволена специфікація та незалежні тести; CAN census сам цього не дає | Керування не реалізовано | Інший адаптер сам по собі не вирішує | Не обходити authorization; не копіювати guessed payload |

## 5. Як працює новий аудит

Перед усіма пробами: ATSP6, ATCAF0, ATCFC0, явний ATCSM1 (silent), ATDPN має підтвердити протокол 6. ATD1 запитує DLC; unsupported reply зберігається, а фактичні `framesWithDlc`/`framesWithoutDlc` показують, що справді прийшло. ATCS зберігається без вигаданого трактування vendor-полів. Відкрита reference: ATCM000/ATCF000.

| ATCM / ATCF | Діапазон ID | ATCM / ATCF | Діапазон ID |
|---|---|---|---|
| 400 / 400 | 400–7FF | 600 / 200 | 200–3FF |
| 700 / 100 | 100–1FF | 780 / 000 | 000–07F |
| 7C0 / 080 | 080–0BF | 7E0 / 0C0 | 0C0–0DF |
| 7F0 / 0F0 | 0F0–0FF | 7F8 / 0E0 | 0E0–0E7 |
| 7FC / 0EC | 0EC–0EF | 7FE / 0EA | 0EA–0EB |
| 7FF / 0E8 | 0E8 | — | — |

Acceptance: `(id & mask) == filter`. Група визначається найстаршим бітом, що відрізняється від 0E9, тому покриття повне й без перекриття. Це CAN-план для 11-bit; відсутність 29-bit traffic не перевіряється. На ELM стандартні CM/CF є приймальними фільтрами [1]; реальна Vgate implementation залишається предметом тесту.

Будь-який ID поза підтвердженою маскою → FILTER_BEHAVIOR_MISMATCH і stop; unconfirmed prompt/timeout/truncation → закриття каналу. Overflow із отриманим prompt фіксується, але наступна незалежна група може бути перевірена. Наприкінці чистої сесії ATD0 (якщо D1 був підтриманий) та ATCRA повертають локальні defaults. При ненадійному prompt нові restore-команди не йдуть; наступна окрема сесія починається з ATZ.

Raw ASCII обмежено першими 2048 байтами, trace — 64 notifications, invalid samples — трьома рядками до 160 символів. Їхні truncation flags окремі від capture truncation; вони не роблять неповний CAN-запис повним. Arrival — вхід у ElmChannel.receive після callback-клонування; parser/lock wait не охоплюють Android stack queue або час у радіоканалі. Всі вимірювання мають монотонний clock.

ReadOnlyPolicy.java і HeatSafety.java залишилися байт-у-байт. Окремий API адаптерної діагностики приймає лише фіксовані receive/status команди з цього плану; ECU payload, CSM0, baud/PP/ST settings і довільні фільтри заборонені. Після першої такої команди канал більше не може виконувати UDS/route/flow-control path, навіть після ATZ. Не додані ECU Reset, Security Access, WriteDID, RoutineControl чи firmware update. `heatingAvailable=false`, `climateStateVerified=false`.

## 6. Vgate, OBD-шлюз і перспектива штатного обігріву

**Vgate придатний для наступного обмеженого тесту та потенційно для вибраного ID/UDS-трафіку.** Придатність для суцільного raw-CAN sniffing не доведена і поточні broad проби її не підтверджують. Acceptance filter корисні, бо скорочують потік до мосту; ліміт кількості кадрів у parser цього не робить. Гарантоване vendor-обмеження кількості ATMA повідомлень для цього firmware не знайдено; непідтверджені ST-команди не додаються.

| Інтерфейс | Яку ланку змінює | Що не вирішує | Коли технічно потрібен |
|---|---|---|---|
| Поточний Vgate BLE + receive masks | Зменшує output до UART/BLE, без покупки | Немає фізичних CAN timestamps/error trace; gateway visibility та HVAC decode | Зараз: один census інших 11-bit IDs |
| Той самий FD+ через Android Classic/SPP | Прибирає BLE із порівняння, якщо цей режим підтриманий конкретним пристроєм | Зберігає ELM/ASCII/UART і OBD network | Лише якщо потрібне відокремлення BLE від іншого bottleneck |
| USB ELM-сумісний, наприклад OBDLink EX | Прибирає radio/телефонний BLE; diagnostic raw protocol лишається interpreter [9] | Не відкриває Fisker gateway, не гарантує lossless monitoring | Умовний контроль транспорту; не рекомендація покупки |
| Native CAN, наприклад PCAN-USB | Прямі CAN frames, 11/29-bit, hardware timestamps; classic CAN [8] | Не відкриває gateway і не надає HVAC semantics | Після filter mismatch/стійкого overflow або коли потрібні retries/error frames; спершу позичити |
| Підтверджений multi-CAN доступ до потрібної фізичної шини | Змінює саме network access | Потрібні pinout, безпечне підключення і decoding | Тільки після доказу, що OBD не надає потрібної мережі |

Історичний owner-журнал у `docs/TECHNICAL-REPORT-UA.md` підтверджує ECC identity на 7F0/7F8 та PKC 21.3 у момент читання. Це факт діагностичної досяжності, не факт пасивного HVAC broadcast або доступного start. Відкриті `puddletools/CAN` (SHA 4654bca7ecb6aa27db114ab40f63d7a1ad675660) і SaavyScripts (eaf134c680f30b04f12ffb99eacffe7531ec90d7) дають діагностичні identity приклади. Перевірених HVAC payload/bit definitions і безпечного start/stop для цієї конфігурації там не знайдено [6]. DBC extended-ID flag не є самостійним доказом фізичної 29-bit шини на нашому OBD.

OceanLink Pulse описує кілька CAN-мереж і custom cabling; його SpiderPort pin numbers не можна переносити на OBD Ocean [7]. FAQ OceanLink Pro описує PKC credentials і власне preconditioning; це не публічна специфікація авторизованого стороннього драйвера для PKC21.3 [7]. Змішані software-версії в автомобілі треба зберігати як факт, а не виводити їх з OS на екрані.

**Продовжувати доцільно лише як обмежене визначення доступності мережі.** Дистанційний запуск/таймер без FOA/O Loop потребує перевіреного авторизованого протоколу, wake/start/stop, актуальної телеметрії та незалежного vehicle-side timeout. Поточний census не замінює цих передумов. Якщо всі чисті non-0E9 групи порожні, broad ATMA більше не є продуктивним напрямом; потрібна topology/authorized PKC documentation, а не ще один косметичний APK чи випадкова покупка адаптера.

## 7. Один наступний контрольний тест і розгалуження рішення

1. Встановити сумісний Network Audit APK поверх поточного застосунку. Авто стоїть у P, штатно активне; клімат увімкнений штатним екраном. Інші OBD-програми закриті.
2. У Fisker Warm натиснути тільки «3. Перевірити інші CAN ID без 0E9», обрати той самий vLinker FD-IOS і залишити застосунок на екрані до завершення (звичайно близько 10 секунд; hard limit 60 с).
3. Експортувати один фінальний JSON. Повторювати попередні OFF/ON проби не потрібно. Усі adapter settings і 12 monitor windows виконує програма; ручних команд немає.

| Результат audit | Технічне рішення |
|---|---|
| OTHER_11BIT_TRAFFIC_OBSERVED; усі групи COMPLETE, ID у масках | Наявний Vgate може отримувати інший вибраний трафік. Зберегти ID/trace як спостереження; наступне decoding потребує незалежного підтвердження. Відсутність overflow із exclusion підтримує flood/output гіпотезу, але не визначає UART проти BLE |
| NO_OTHER_IDS_IN_SAMPLED_WINDOWS; усі групи COMPLETE | Не знайдено інших ID у цих послідовних 500-мс вікнах. Припинити broad ATMA; перевіряти документовану topology/authorized route. Повільні, dormant, інші формати/мережі не виключені; новий адаптер на тому самому порту не гарантовано допоможе |
| FILTER_BEHAVIOR_MISMATCH або відхилена CM/CF/CSM1 | Firmware/filter semantics не підтверджені. Припинити цей режим; для доказу потрібен відомий receive-filter interface або позичений native CAN, не вгадування settings |
| FILTERED_CAPTURE_INCOMPLETE / BUFFER FULL в інших групах | 0E9 не є єдиним достатнім поясненням. Використати raw/DLC/ATCS і callback timings; якщо parser/lock невеликі, залишаються adapter/output/physical hypotheses. Native capture виправданий для їх розділення |
| Timeout, unconfirmed stop, низька/невідома напруга | Сесія закривається. Немає висновку про видимість шин або HVAC; автоматичних retry і ECU-команд немає |

Це один короткий безпечний тест, необхідний тому, що офлайн неможливо виміряти adapter masks, реальний CAN stream і OBD routing. Навіть чистий результат не підтверджує автоматично роботу клімату.

## 8. Офлайн-перевірки, APK і контроль змін

Виконано 152 core + 58 CAN regression checks = 210; всі успішні. Нові meaningful regressions перевіряють повне покриття ID, fixed allowlist та injection/control rejection, неможливість повторного UDS використання diagnostic channel, fragmentation/prompt/timeout, DLC, bounded trace і незалежність його лімітів від frameCount. Android-код компілюється з JDK 17, SDK 35 і Build Tools 35.0.0. Hardware/BLE/автомобіль цими тестами не перевірялись.

CI вже налаштовано запускати всі suites на цій гілці, перевіряти SDK, відновлювати лише існуючий key, fail на unknown certificate, підписувати/перевіряти APK та прибирати тимчасові signing files. Нових workflow-змін не потрібно. Перед передачею перевіряється успішний run саме фінального коміту та його завантажений artifact; посилання і SHA додаються до супровідного результату.

Сумісний profile lab02: `com.fiskerwarm.lab02`, сертифікат SHA-256 `848edb87d8b74d2c20efd191a0fa3086c2466aa9a1169628826ac8f5fb78983e`. Видима версія лишається 0.3.3-lab; Android versionCode 9 оновлює встановлений 8, бо додано функціональний режим. Канонічний source package/default profile lab03 збережено. Перевірка v2/v3, package, certificate, permissions, manifest і compatible update повинна пройти всі 19 artifact checks. Приватних ключів/паролів у коді, report чи artifacts немає. PR #2 залишається draft і unmerged; Issue #1 не закривається без фізичного підтвердження.

## 9. Первинні джерела та межі їх використання

[1] ELM Electronics, manufacturer datasheet ELM327DSJ: https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf — CF/CM/CRA, CSM, CS, DLC, monitor і BUFFER FULL. Описує ELM, а не внутрішню firmware Vgate.

[2] Vgate, офіційний FD+: https://www.vgatemall.com/products-detail/i-23 — AT/ST, protocols, Android/iOS connection modes.

[3] Vgate, legacy FD+ product description: https://www.vgatemall.net/productinfo/549489.html — номінальні USART buffer/baud. Немає доказу їх фактичного використання нашим пристроєм.

[4] Fiskeretta, первинний код, коміт 2254dbc4845346523da91c3a1a7b85018767de7b, `no_filter_sweep()` / `monitor_bus()`: https://github.com/Fiskeretta/fiskeretta-diagnostics/blob/2254dbc4845346523da91c3a1a7b85018767de7b/fiskeretta/uds.py — незалежний опис 0E9 flood і adapter filtering. Active sweep-команди цього проєкту не копіювалися.

[5] Linux kernel can327, документація автора драйвера: https://docs.kernel.org/networking/device_drivers/can/can327.html — обмеження ELM CAN interface та відмінності сумісних реалізацій.

[6] Puddletools, відкриті джерела: https://github.com/puddletools/CAN та https://github.com/puddletools/SaavyScripts — перевірено конкретні commits, наведені в розділі 6; знайдені identity приклади не підтверджують HVAC start.

[7] Розробник OceanLink: https://www.oceanlinkpulse.com/ і https://www.oceanlinkpro.com/faq — multi-CAN/custom cabling та заявлена авторизована PKC-функціональність. Немає публічної wire specification для нашого стороннього APK.

[8] PEAK-System, PCAN-USB: https://www.peak-system.com/products/hardware/external-pc-interfaces/pcan-usb/?L=1 — native classic CAN, 11/29-bit, hardware timestamps. Це порівняння інтерфейсів, не рекомендація покупки.

[9] OBDLink EX: https://www.obdlink.com/products/obdlink-ex/ — USB OBD interface; не доказ додаткової Fisker network access.

[10] AndrOBD, первинний Android-проєкт: https://github.com/fr3ts0n/AndrOBD — diagnostic polling/transports; не доказ lossless ATMA або HVAC-протоколу.

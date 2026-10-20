# Fisker Warm — CAN RX burst і завершення контролю, етап 7.5

20.10.2026. Ocean 2023 EU / OS 2.1 / PKC 21.3; S24 Ultra API 36; vLinker FD-IOS.

**Доведено дві окремі програмні обставини.** По-перше, 0.3.5 показує сумарні 40 кадрів, включно зі stop drain, поряд із 53 мс до рішення зупиняти; це не вимірювання «40 фізичних кадрів за 53 мс». По-друге, CanRxAudit вимагає безвтратного контрольного capture і тому обриває експеримент до 373, хоча позитивні повні рядки 0E9 отримані та stop/reset підтверджені. Класифікація самого capture як неповного правильна. Виправлено поділ доказів присутності RX і повноти, додано суворо перевірене відновлення адаптера перед наступною фазою. Фізичний механізм повторів усередині CAN/Vgate не встановлено; його невизначеність описано окремо. Реальний запуск HVAC застосунком не реалізовано.

## Джерело і відокремлення історії

Основний файл: `Fisker-Warm-HVAC-2026-10-20T07-32-39.426209Z.json`, 161818 bytes, SHA-256 `e64d837495bd40e2d34828544a98ac7b75b5fd7d2fe35df78ccdc549a78a266c`.

У bundle є сім записів: п'ять історичних 0.3.4, один новий preflight 0.3.5 та **один** CAN_RX_CONTROL 0.3.5, початок 07:32:26.398847Z. Аналіз вибирає mode + appVersion, а не root appVersion. `hvacCapture` і `canRxAudit[0].capture` — дві однакові серіалізовані копії одного capture, а не два вимірювання. Так само raw `bleIo.events` і capture.notifications не додаються одне до одного.

Runtime встановленої 0.3.5 — [859ff6662af3c4ce76f3c2f96ce55acec5c28fac](https://github.com/bogdanlin2/Fisker-Warm1/commit/859ff6662af3c4ce76f3c2f96ce55acec5c28fac), [APK CI 39384997170](https://github.com/bogdanlin2/Fisker-Warm1/actions/runs/39384997170). Docs-only 8d2d07d не змінює runtime. Попередній [звіт 7.4](CAN-RX-AUDIT-STAGE7-4-UA.md) використаний як baseline; висновки тих нульових captures не переносяться на новий сеанс.

Відтворюваний незалежний аналіз: [analyze_can_rx_burst.py](../scripts/analyze_can_rx_burst.py). Він читає raw bytes, розбиває CR/LF і сам перевіряє ID/DLC/байти, не викликаючи Android-парсер. [Повний результат](../verification/stage7-5/measured-burst-analysis.json), [raw fixture](../verification/stage7-5/recorded-burst-fixture.json), [точні сім пакетів TSV](../verification/stage7-5/recorded-burst.tsv). Публічні файли не містять MAC або ключів.

## Точна часова реконструкція

Нуль відліку capture: `System.nanoTime = 71409250773330`, epochMillis `1792481548637` (UTC 07:32:28.637). Це host monotonic clock; він не є CAN hardware timestamp. GATT API queue success, host write callback, ELM prompt і CAN payload — різні події.

| Подія | Host timestamp, ns | Від початку, мс | Від попередньої події, мс | Повних CAN-рядків сумарно / примітка |
|---|---:|---:|---:|---|
| ATMA TX_REQUEST | 71409251963799 | 1.190469 | — | status -1 |
| ATMA TX_QUEUE_RESULT | 71409253964059 | 3.190729 | 2.000260 | status 0 |
| ATMA TX_CALLBACK | 71409277644632 | 26.871302 | 23.680573 | status 0 |
| ATMA TX_COMPLETE | 71409278004371 | 27.231041 | 0.359739 | status 0 |
| RX notification 1 | 71409298581246 | 47.807916 | 20.576875 | 6; 200 bytes |
| SPACE TX_REQUEST | 71409304651351 | 53.878021 | 6.070105 | status -1 |
| SPACE TX_QUEUE_RESULT | 71409307541559 | 56.768229 | 2.890208 | status 0 |
| RX notification 2 | 71409316394736 | 65.621406 | 8.853177 | 12; 200 bytes |
| RX notification 3 | 71409338084476 | 87.311146 | 21.689740 | 19; 200 bytes |
| RX notification 4 | 71409353022653 | 102.249323 | 14.938177 | 25; 200 bytes |
| SPACE TX_CALLBACK | 71409355575882 | 104.802552 | 2.553229 | status 0 |
| SPACE TX_COMPLETE | 71409355679007 | 104.905677 | 0.103125 | status 0 |
| RX notification 5 | 71409369989788 | 119.216458 | 14.310781 | 32; 200 bytes |
| RX notification 6 | 71409385783642 | 135.010312 | 15.793854 | 37; 200 bytes; один пошкоджений рядок |
| RX notification 7 | 71409400388434 | 149.615104 | 14.604792 | 40; 105 bytes; пошкоджений рядок, BUFFER FULL → STOPPED → > |

Третій повний рядок міститься вже у першому 200-byte notification, який завершує **шість** повних рядків і починає сьомий. Його RX timestamp — 71409298581246 (+47.807916 мс). Точний CPU-момент розбору третього рядка 0.3.5 не журналювала; він лежить між входом цього callback та рішенням/запитом SPACE. SPACE TX_REQUEST іде через **6.070105 мс** після входу callback. Інших RX до нього немає, тому до рішення зупинки розпарсено рівно шість повних кадрів.

SPACE queue прийнята через 2.890208 мс після TX_REQUEST. Її GATT callback — через **50.924531 мс** після TX_REQUEST. На той час через RX уже завершено 25 повних рядків; ще 15 завершуються пізніше. Фінальний пакет на +149.615104 мс містить по порядку останні CAN bytes, `BUFFER FULL`, `STOPPED`, prompt. Окремих timestamps цих трьох статусів немає: вони в одному notification.

Отже, **BUFFER FULL отримано після запиту SPACE на 95.737083 мс і після його callback на 44.812552 мс**. Це порядок доставки host-у. Точний момент, коли фізично заповнилася внутрішня черга адаптера, невідомий: текст міг чекати в чергах. Приписувати переповнення саме подіям після фізичного прийняття SPACE не можна.

Після prompt початковий застосунок уже виконав успішне відновлення: ATZ TX_REQUEST +156.430989 мс; callback +193.018750 мс; ELM327 v2.2 banner +1110.953020 мс. `adapterResetConfirmed=true`. Новий код використовує цей вже виміряний adapter-only механізм і повторює весь пасивний startup до наступного ID.

## Звідки взялися 40 кадрів і часова аномалія

У raw є **42 рядки, що починаються з 0E9**: 40 повних валідних восьмибайтових і два пошкоджених:

- `0E9 8 2D F0    9E  FF FF`
- `0E9 8 2D F0 FF F`

Усі 40 повних рядків містять `2D F0 FF FF 9E 57 FF FF`. Сім notification payload різні; фрагментація проходить крізь межі текстових рядків. Незалежний лічильник дає рівно 40 — стільки ж, скільки Android frames[]. Жодних повторних ATMA немає, SPACE один. `bleIo.complete=true`, droppedEvents=0, droppedFrames=0, droppedPackets=0. Не виявлено повторного внесення тих самих packets парсером. Це не доводить відсутності повторів чи кешу **до Android callback**.

Розподіл повних рядків по пакетах: **6, 6, 7, 6, 7, 5, 3**. До SPACE — 6; після SPACE — 34. Усі bytes збережені; два пошкоджені рядки не перетворені на кадри. Початок останнього notification — +149.615104 мс, від першого до останнього — 101.807188 мс.

`captureElapsedMillis=53` у старому [ElmChannel.monitor](https://github.com/bogdanlin2/Fisker-Warm1/blob/859ff6662af3c4ce76f3c2f96ce55acec5c28fac/app/src/main/java/com/fiskerwarm/lab/core/ElmChannel.java#L158-L220) вимірює тільки час до stop decision. Потім той самий Stream продовжує приймання близько 98 мс, а snapshot включає цей хвіст. Ділити 40 на 53 мс неправильно. І навіть поділ на близько 150 мс **не дає частоти фізичних нових CAN повідомлень**: bytes доставляються пакетно, timestamps усіх рядків одного пакета однакові.

Використана матриця декларує 0E9 через 20 мс. Для 40 окремих послідовних періодичних оновлень це близько 780 мс між першим і останнім; такого спостереження на шині ми не маємо. Поправка 53→149.6 мс сама по собі не доводить узгодженість із 20 мс. Залишаються повторні CAN transmissions, інший реальний період/несумісна матриця або накопичення/відтворення даних адаптером. Сталий payload не дозволяє відрізнити їх.

## Що доведено про BUFFER FULL і що ще невідомо

| Рівень / механізм | Висновок із цього журналу |
|---|---|
| Android storage buffer | Не переповнений: 7 packets із ліміту 4096, 40 frames із 4096, 1305 raw bytes із 512000; truncation/drop counters нульові. Ліміти не збільшувалися. |
| Адаптерний output buffer | **Адаптер повідомив BUFFER FULL у сирому тексті.** Це не повідомлення, створене Android для власного переповнення. Два пошкоджені рядки також є в raw. |
| Надмірний потік / повтори до BLE | Сумісний із даними: багато однакових рядків на виході адаптера; реальний темп нових CAN updates не виміряно. |
| BLE/UART черги | Є пакетна доставка 200-byte блоками й тривалий хвіст після stop. Місце, місткість і час заповнення кожної внутрішньої черги невідомі. |
| Android processing | Після першого callback до SPACE REQUEST лише 6.07 мс. Немає доказу, що головна причина — великий app lock або CPU backlog. Затримку до входу Android callback виміряти цим журналом не можна. |
| Завершення MA | Запит SPACE і успішний callback є, STOPPED/prompt також є. Хвіст після SPACE не доводить, що stop проігноровано: bytes могли вже чекати передачі. |
| CM/CF | Прийняті рядки всі мають запитаний ID. Доказів, що саме цей фільтр породив повтори чи overflow, немає. Заміна на CRA не є доведеним способом зменшити потік того самого ID. |

[ELM327 v2.2 datasheet](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf), printed pp. 21, 75–76, 88, описує BUFFER FULL як переповнення внутрішнього RS232 TX buffer: надходження випереджає виведення, дані втрачаються. Зазначені для оригінального ELM 512 bytes не можна переносити на Vgate як виміряну місткість. Той самий документ вимагає після stop дочекатися prompt і для продовження почати нове приймання. У MA стандартного ISO CAN немає документованого параметра count; `MP ... n` стосується J1939 і не замінює SP6/11-bit монітор. ST/timeout для звичайного request не є квотою MA. ATS0/ATH0 могли б скоротити текст, але не забезпечують точного числа фізичних кадрів; від заголовка для перевірки ID не відмовляємося.

**Конкретна нова фізична гіпотеза — відсутність ACK та CAN retransmissions.** У [первинному експерименті CSS Electronics, test #4](https://www.csselectronics.com/pages/can-bus-errors-intro-tutorial) після від'єднання acknowledging node silent logger записує високочастотні повтори одного кадру: передавач переходить в Error Passive і продовжує повторні спроби. Це могло б пояснити сталі payload та номінальний період, що не відповідає host burst. Але немає доказу, що OBD-сегмент власника має саме таку топологію, що на ньому немає іншого ACK node, або що Vgate не повторює кешовані дані. ACK/error flags/CAN hardware timestamps не записані. **Це гіпотеза, а не встановлений діагноз Fisker.** CSM0 не застосовується; фізичну тишу CSM1 ACK не доводить.

Додатково перевірено [офіційний OBDLink FRPM F](https://www.obdlink.com/frpm), §§8.8–8.9: для відповідних STN/OBDLink є `STM n` з поточними filters та автоматичним завершенням; `STMA n` читає всі повідомлення й непридатний до нашої заборони broad monitor. [Vgate](https://www.vgatemall.com/) рекламує сумісність AT/ST, але це не таблиця підтримки опції n, версії firmware, raw format та silent-mode semantics власного FD-IOS. Рядок ATI=ELM327 v2.2 цього не встановлює. Тому не підміняємо одну невизначеність новою: **STM/STMA, STCMM та непідтверджені suffix до MA в APK не додані**. Підтримка bounded STM могла б бути окремим майбутнім шляхом лише після підтвердження саме firmware; для нинішнього binary control вона не потрібна.

## Аудит алгоритму та обране виправлення

[CanRxAudit.usable/run 0.3.5](https://github.com/bogdanlin2/Fisker-Warm1/blob/859ff6662af3c4ce76f3c2f96ce55acec5c28fac/app/src/main/java/com/fiskerwarm/lab/core/CanRxAudit.java#L33-L43) вимагає `!capture.incomplete()`. BUFFER FULL і invalidFrameLines роблять capture неповним незалежно від 40 валідних рядків. Тому CONTROL_CAPTURE_INCOMPLETE і відсутність CF373/MA для ECC — **детермінований результат коду**, не доказ недоступності 373. Класифікація неповноти була правильною; не було окремого дозволеного шляху resynchronization для позитивного, але втратного контролю.

[CanCapture](../app/src/main/java/com/fiskerwarm/lab/core/CanCapture.java) додає raw packet перед байтовим розбором і ніколи не повторює input сам. [GattWriteGate](../app/src/main/java/com/fiskerwarm/lab/core/GattWriteGate.java) серіалізує write/queue/callback; [BleIoJournal](../app/src/main/java/com/fiskerwarm/lab/core/BleIoJournal.java) журналює callback boundary до routing. Ці два класи та PassiveNetworkPlan побайтно незмінні від 0.3.5. Прискорювати stop прямим паралельним write із RX callback не потрібно: це порушило б single-flight GATT і все одно не прибрало б already queued bytes.

Цільова **0.3.6-lab / versionCode 12** змінює лише потрібну поведінку:

1. `captureElapsedMillis` явно означає час до stop decision; `frameCountScope` — включно з drain. Додані count/bytes/notifications на межі acquisition, drainFrameCount, captureTotalMillis, точні acquisition/recording ends, host RX/CPU моменти першого порога, processing/lock metrics і invalidLineSamples. Unknown boundary у replay залишається unknown, синтетика не стає новим фізичним RX.
2. Повнота `Snapshot.incomplete()` **не послаблена**. BUFFER FULL, malformed rows, raw bytes і всі втрати залишаються в capture. Позитивна ознака «повний 0E9 рядок спостерігався» відокремлена від «capture повний» та від «можна перейти далі».
3. Лише для контрольного 0E9 дозволено розглянути підтверджений overflow prefix: є валідний 0E9 до stop decision, MA write підтверджено, prompt/stop відомі, усі повні кадри мають точний ID, raw prefix не обрізаний. **Не допускаються** CAN error, сторонній ID, GATT/stop error, unknown prompt, app storage loss, non-ASCII/overlong lines, late start, session limit або USER_STOP. Самого frameCount>0 недостатньо.
4. Перед наступним ID такий втратний контроль обов'язково проходить **ATZ → точний чистий ELM banner → ATE0 → ATI з тим самим banner → ATRV у безпечному діапазоні → ATL0/ATS1/ATH1/SP6/CAF0/CFC0/CSM1/D1 ACK → DPN=6**. Тільки тоді AR/CM7FF/CF373 і MA. ResetOnly guard у ElmChannel збережений: до ATZ він не допускає навіть AR. Забруднений banner, зміна identity, низька напруга або відмова будь-якого setting припиняє тест.
5. ECC 373 як і раніше потребує повного bounded observation. Втратний ECC capture не приймається за повне вимірювання і не проходить control exception. Звичайні exact CM/CF і, за потреби, exact CRA лишилися. Постконтроль 0E9 зберігається повністю; за його втрат код результату містить `_WITH_CONTROL_LOSS`, root.incomplete=true, allCapturesComplete=false. differentialPhasesCompleted, controlBefore/AfterRxObserved та eccCapturesComplete — окремі поля.
6. UI явно показує неповноту й BUFFER FULL, а загальний count підписаний «із drain». Таймери 1 с для контрольного MA / 3 с для ECC, поріг 3, розміри buffers та GATT write mechanics **не змінено**. Немає нових adapter command families чи CAN payload. Final ATZ скидає тільки адаптер, не ECU. ReadOnlyPolicy/HeatSafety незмінні.

Це **не усунення фізичного переповнення Vgate** і не гарантія exactly-three фізичних transmissions. Це технічно обмежений спосіб завершити binary presence experiment, чесно зберігаючи втратність контрольних потоків. За непридатного prefix або невдалого reset він не продовжиться.

## Автоматизоване підтвердження

[CanBurstChecks](../tests/CanBurstChecks.java) відтворює саме сім записаних packets у вихідному порядку через асинхронний fake GATT: перший packet після MA completion, tail після SPACE, SPACE callback між четвертим і п'ятим RX, BUFFER FULL/STOPPED/prompt у сьомому. Відтворення підтверджує 40/2/1305, acquisition 6 / drain 34 і незмінні error flags. Це модель host-подій, а не модель фізичної CAN-шини або нове автомобільне вимірювання.

Окремо автоматизовано: завершення всього differential із двома втратними controls та нульовим ECC; отримання ECC як окрема ознака; правильний reset/re-arm порядок; забруднений reset banner, ATI/voltage/CSM1/DPN відмови; 15 незалежних заборон recovery; unknown stop; ECC overflow. Усі outbound bytes перевіряються існуючим fixed passive firewall. [Python-перевірки](../tests/test_can_burst.py) незалежно рахують raw lines/timestamps і читають Java JSON: неповнота та SYNTHETIC_TEST не зникають, payload/raw bytes не обрізаються. Усі чинні регресії також запускаються. Точні CI totals, фактичний APK hash/сертифікат і посилання фіксуються в `verification/stage7-5/DELIVERY-UA.md` після збірки.

На реальному Vgate новий шлях ще не виконано. Фізичну причину однакових frames не можна остаточно відрізнити без ACK/error/wire timestamps чи підтверджених firmware даних. Купівля обладнання, прошивання, зміна CSM0 або активний CAN для цього етапу не потрібні й не пропонуються.

## Один наступний фізичний тест

Підстава: 0.3.5 **не має виконаної фази 373**; нова версія перевіряє конкретний новий перехід після переповнення. Повторювати старий HMI ON/OFF або 30-секундні записи не потрібно.

Встановити сумісний APK 0.3.6 поверх 0.3.5. Авто пробуджене штатно, P, стоянкове гальмо, ключ і власник поруч, немає попереджень живлення/оновлення ПЗ; інші OBD-програми закриті. Позначити умови → «Підключити Vgate» → один раз «Контроль CAN RX · 0E9 → 373 → 0E9». Клімат не перемикати. Якщо 0E9 переповниться, очікується видиме «НЕПОВНИЙ», recovery states і **потім ECC_VISIBILITY**, за нульового ECC — ECC_ALTERNATE_FILTER, далі CONTROL_AFTER. «Зупинити» припиняє експеримент; при unknown prompt/reset чи попередженні авто від'єднати Vgate. Жодного reset ECU, security, UDS write, CAN wake-up або авторизаційного обходу.

**Критерій завершення цього контролю:** differentialPhasesCompleted=true, controlBefore/AfterRxObserved=true, eccCapturesComplete=true. Якщо контролі втратні, allCapturesComplete має лишитися false та BUFFER FULL — true; це не «усе справне». eccRxObserved покаже тільки наявність свіжих для цього host-сеансу прийнятих 373 рядків, не HVAC ON. Нуль ECC не доводить фізичної відсутності сигналу всередині авто. Експортувати один JSON і після завершення від'єднати Vgate.

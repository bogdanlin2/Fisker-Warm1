# Fisker Warm Lab 0.3.3

Канонічні вихідні файли: https://github.com/bogdanlin2/Fisker-Warm1

Проєкт розміщений безпосередньо в корені репозиторію: `app/`, `scripts/`, `tests/`, `docs/`. Наступні зміни виконуються у цих файлах; ручне перенесення ZIP більше не потрібне.

Версія 0.3.3 записує короткі пасивні CAN-вікна тривалістю 0.5 с. Це дослідницький застосунок: дистанційний обігрів заблокований і не перевірений на автомобілі. `ReadOnlyPolicy` та `HeatSafety` збережені з вихідного ZIP.

## Автоматична збірка

`.github/workflows/android.yml` запускається після кожного push до `main` або вручну через GitHub Actions → Build Fisker Warm 0.3.3 APK → Run workflow. Використовуються JDK 17, офіційні Android SDK Platform 35 і Build Tools 35.0.0.

1. Перевірка всіх 151 офлайн-тесту.
2. Відновлення існуючого власницького ключа із GitHub Actions Secrets і вибір сумісного профілю за його публічним сертифікатом.
3. Збірка через `scripts/build_android.py`.
4. Перевірка через `scripts/verify_apk.py`: підпис v2/v3, точна пара сертифікат/package для вибраного профілю, versionCode 7, версія 0.3.3, Android Manifest і точний набір дозволів.
5. Збереження APK вибраного профілю та звіту перевірки в Artifacts на 90 днів.

### Сумісні профілі

| Профіль | applicationId | APK | Сертифікат SHA-256 |
| --- | --- | --- | --- |
| `lab03` — типовий | `com.fiskerwarm.lab03` | `Fisker-Warm-Lab-0.3.3.apk` | `3c1716e2…b39be74` |
| `lab02` — оновлення встановленої 0.2.0 | `com.fiskerwarm.lab02` | `Fisker-Warm-Lab-0.3.3-Update02.apk` | `848edb87…fb78983e` |

Старий APK 0.2.0 перевірено безпосередньо: package `com.fiskerwarm.lab02`, versionCode 3, сертифікат `848edb87d8b74d2c20efd191a0fa3086c2466aa9a1169628826ac8f5fb78983e`. Для оновлення без видалення застосунку потрібні і той самий package, і той самий сертифікат. Збірка Update02 має versionCode 7 і зберігає цю пару.

Вихідний Android Manifest і типовий профіль `lab03` залишаються `com.fiskerwarm.lab03` з його попереднім сертифікатом. Профіль `lab02` змінює package лише в згенерованому Manifest у `build/`. Код автомобільного протоколу спільний і не змінюється.

Поточні GitHub Secrets містять перевірений ключ Lab02, тому workflow автоматично збирає Update02. Невідомі сертифікати не приймаються. Для Lab03 потрібен його власний наявний ключ; застосовувати до нього ключ Lab02 заборонено.

Необхідні repository Actions Secrets:

| Назва | Значення |
| --- | --- |
| `FISKERWARM_KEYSTORE_B64` | Base64 наявного власницького ключа Lab02 або Lab03, без переносів рядків |
| `FISKERWARM_SIGNING_PASSWORD` | Відповідний наявний пароль ключа |

Матеріал ключа не входить до файлів репозиторію, Artifacts або кешів. Якщо секрети відсутні, workflow зупиняється з конкретною помилкою; новий ключ автоматично не створюється. Та сама signing identity потрібна для оновлення відповідного вже встановленого застосунку без видалення даних.

## Локальна збірка

Відновити наявний ключ і пароль за інструкцією `docs/SIGNING-RESTORE-UA.md`, після чого:

```sh
python3 scripts/test_core.py
ANDROID_SDK_ROOT=/path/to/android-sdk python3 scripts/build_android.py
python3 scripts/verify_apk.py --build-tools /path/to/android-sdk/build-tools/35.0.0
```

Готовий типовий файл: `dist/Fisker-Warm-Lab-0.3.3.apk`. Для оновлення встановленої 0.2.0 відновити її старий ключ і додати `--profile lab02` до команд збірки та перевірки; вихідний файл — `dist/Fisker-Warm-Lab-0.3.3-Update02.apk`.

Для порівняння підпису й applicationId з конкретним старим файлом додати `--previous-apk /path/to/old.apk` до команди перевірки. `--require-compatible-update` робить невідповідність package, підпису або versionCode помилкою перевірки. Успішна перевірка APK не є перевіркою BLE, CAN-адаптера чи обігріву на автомобілі.

Історичні звіти в `docs/` описують попередні версії; поточний результат кожної збірки міститься у її Verification artifact.

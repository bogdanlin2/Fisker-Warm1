# Fisker Warm Lab 0.3.3

Канонічні вихідні файли: https://github.com/bogdanlin2/Fisker-Warm1

Проєкт розміщений безпосередньо в корені репозиторію: `app/`, `scripts/`, `tests/`, `docs/`. Наступні зміни виконуються у цих файлах; ручне перенесення ZIP більше не потрібне.

Версія 0.3.3 записує короткі пасивні CAN-вікна тривалістю 0.5 с. Це дослідницький застосунок: дистанційний обігрів заблокований і не перевірений на автомобілі. `ReadOnlyPolicy` та `HeatSafety` збережені з вихідного ZIP.

## Автоматична збірка

`.github/workflows/android.yml` запускається після кожного push до `main` або вручну через GitHub Actions → Build Fisker Warm 0.3.3 APK → Run workflow. Використовуються JDK 17, офіційні Android SDK Platform 35 і Build Tools 35.0.0.

1. Перевірка всіх 151 офлайн-тесту.
2. Відновлення існуючого ключа Lab03 із GitHub Actions Secrets.
3. Збірка через `scripts/build_android.py`.
4. Перевірка через `scripts/verify_apk.py`: підпис v2/v3, відбиток сертифіката, `com.fiskerwarm.lab03`, versionCode 7, версія 0.3.3, Android Manifest і точний набір дозволів.
5. Збереження `Fisker-Warm-Lab-0.3.3-APK` та звіту перевірки в Artifacts на 90 днів.

Необхідні repository Actions Secrets:

| Назва | Значення |
| --- | --- |
| `FISKERWARM_KEYSTORE_B64` | Base64 наявного власницького `Fisker-Warm-Lab03-Signing-Key.p12`, без переносів рядків |
| `FISKERWARM_SIGNING_PASSWORD` | Пароль із наявного власницького `Fisker-Warm-Lab03-Signing-Password.bin` |

Матеріал ключа не входить до GitHub, Artifacts або кешів. Якщо секрети відсутні, workflow зупиняється з конкретною помилкою; новий ключ автоматично не створюється. Та сама signing identity потрібна для оновлення вже встановленого `com.fiskerwarm.lab03` без видалення даних.

## Локальна збірка

Відновити наявний ключ і пароль за інструкцією `docs/SIGNING-RESTORE-UA.md`, після чого:

```sh
python3 scripts/test_core.py
ANDROID_SDK_ROOT=/path/to/android-sdk python3 scripts/build_android.py
python3 scripts/verify_apk.py --build-tools /path/to/android-sdk/build-tools/35.0.0
```

Готовий файл: `dist/Fisker-Warm-Lab-0.3.3.apk`.

Для порівняння підпису й applicationId з конкретним старим файлом додати `--previous-apk /path/to/old.apk` до команди перевірки. Успішна перевірка APK не є перевіркою BLE, CAN-адаптера чи обігріву на автомобілі.

Історичні звіти в `docs/` описують попередні версії; поточний результат кожної збірки міститься у її Verification artifact.

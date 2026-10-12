# Збірка Fisker Warm Lab 0.3.0

Це наявна офлайн-дослідницька версія 0.3.0. applicationId `com.fiskerwarm.lab03`, versionCode `4`, versionName `0.3.0-lab`. Не починати 0.4.0, не додавати нових функцій і не змінювати протокол.

Код `ReadOnlyPolicy`, `HeatSafety`, `ProbeGate`, `ElmChannel`, усі інші Java-файли, Android Manifest і штатні скрипти збірки/тестування збережені без змін від вхідного архіву. Передача неперевірених команд і запуск клімату залишаються заблокованими.

## Відтворення

Потрібні JDK 17+, офіційний Android SDK Platform 35 і Build Tools 35.0.0.

```bash
python3 scripts/test_core.py
ANDROID_SDK_ROOT=/path/to/sdk python3 scripts/build_android.py
python3 scripts/verify_apk.py --build-tools /path/to/sdk/build-tools/35.0.0
```

Очікується `TOTAL 146 checks passed`. Результат збірки - `dist/Fisker-Warm-Lab-0.3.0.apk`.

Перш ніж повторно підписувати APK для оновлення встановленої версії, відновити власницький ключ за `docs/SIGNING-RESTORE-UA.md`. Приватний ключ і пароль не включені до вихідного ZIP.

Актуальні журнали цієї збірки містяться в `verification/0.3.0-build/`. Успадковані журнали старих версій не є доказом поточної збірки. На Samsung і автомобілі цю збірку не випробовували. Наявність готового APK не означає, що обігрів працює.

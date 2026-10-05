# Healfi: концепция и реальные Android-снимки

[Пять экранов концепции](concept.html) и [монтаж](concept-montage.png) — HTML DEMO.
[Галерея до/после](android-comparison.html) показывает raw Android PNG.

**До:** [before/home.png](before/home.png), source f54,
debug APK `575060d…`, сессия **4 октября 2026**.
[Метаданные](../../docs/verification/healfi-scenario-before-2026-10-05.json)
сохраняют exact source/APK/PNG SHA и синтетическую VirtualPump фикстуру,
loop OFF. Это архивная съёмка; времена и показания двух сессий различаются.

**После:** source `007d5c5c20f0c2bf43a4ad62d4c1c51fef1c440b`,
signed release SHA-256
`9bfdc549f20f7d17951e973da80c78e00c4dba3450722f8e367e2a1e211bd9d9`.
[Native-протокол](../../docs/verification/healfi-scenario-ui-2026-10-05.json)
фиксирует текущие функциональные PASS, visual FAILED и NOT RUN.
USDA → 105 г → Wizard 24 г → original confirmation/cancel прошёл с 0/0 в базе.
Отдельное подтверждение на VirtualPump дало две записи: 2,30 Ед NORMAL и 24 г;
штатная история показала обе. Native total: **11 PASS / 4 visual FAILED /
12 NOT RUN**, 17 стабильных снимков. Поиск настроек и главная Dark при 100% прошли;
весь Dark-сценарий и Food/Wizard при 200% не проверялись.
Full-width подпись Wizard при 100% прошла. Food IME при 100%, перенос COB внутри слова
и перекрытие подписей штатного графика, а также переносы внутри слов
на главной Dark при 200% — **FAILED**; полная визуальная приёмка
не заявлена. Алгоритмы дозирования не изменены.

[CI 37254928995](https://github.com/rw404/AndroidAPS/actions/runs/37254928995)
завершился SUCCESS: **572 выбранных теста, 0 failures/errors/skips**.
[Build provenance](../../docs/verification/healfi-scenario-build-provenance-2026-10-05.json)
и [source audit](../../docs/verification/healfi-scenario-source-audit-2026-10-05.json)
отделены от native runtime.
Старые [a0f](../../docs/verification/healfi-scenario-archive-2026-10-05-a0f44f5.json),
[377](../../docs/verification/healfi-scenario-archive-2026-10-05-377f6ec.json)
и [4c](../../docs/verification/healfi-scenario-ui-crash-2026-10-05-4c39cd3.json)
не входят в финальные after для 007d.

[Генератор](make-android-comparison.py) проверяет exact source/APK,
raw PNG SHA/размеры, native foreground до/после, evidence приложения и
стабильность состояния. Нестабильный `diagnostic-home-initial-loading`
исключён из галереи. Полные экраны и пиксели сохранены без ретуши.
Галерея переносится вместе с относительными PNG; HTML сам по себе
не содержит изображения.

Повторная генерация из опубликованного evidence:

```sh
python3 design/scenario/make-android-comparison.py \
  --before-json docs/verification/healfi-scenario-before-2026-10-05.json \
  --after-json docs/verification/healfi-scenario-ui-2026-10-05.json \
  --expected-after-source 007d5c5c20f0c2bf43a4ad62d4c1c51fef1c440b \
  --after-home-name home-fresh \
  --output design/scenario/android-comparison.html \
  --screenshot-dir design/scenario
```

[Current negative guards](../../docs/verification/healfi-scenario-gallery-guards-2026-10-05.json)
прошли 5/5 проверок отклонения чужого source/APK, отсутствующего native evidence,
чужого foreground и нестабильного кадра.
[Browser report](../../docs/verification/healfi-scenario-gallery-browser-2026-10-05.json)
прошёл: **1440×1000 / 360×800, 18 raw PNG, 0 overflow/errors**,
PNG-хеши сохранены. Область — HTML-галерея.
`--allow-pending` предназначен только для явно помеченного локального черновика.

Проверка относится к Android 15 / API 35, синтетическим данным, VirtualPump и
loop OFF. OnePlus 15, xDrip+ BG, Medtronic 722, OrangePro/RileyLink,
реальная доставка, closed loop и клиническая валидация **NOT RUN**.
TCG с увеличенными deadlines не подтверждает скорость телефона.
Полный перечень непройденного:
[результаты](../../docs/healfi-scenario-results.md).

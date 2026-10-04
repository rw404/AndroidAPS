# Пуш и Actions для rw404/AndroidAPS

Права владельца репозитория и approvals в чате не меняют права токена интеграции.
Предыдущие попытки Git push и записи ветки через подключённый GitHub получили
HTTP 403 `Resource not accessible by integration`.

## Подключение Codex

Интеграция называется **ChatGPT Codex Connector**, разработчик — **openai**.
Если её нет в списке приложений GitHub, начните установку:

1. Откройте [установку Connector](https://github.com/apps/chatgpt-codex-connector/installations/new),
   выберите аккаунт `rw404`, затем **Only select repositories → AndroidAPS**.
2. Откройте [Codex Cloud → Connectors](https://chatgpt.com/codex/cloud/settings/connectors)
   и завершите **Connect GitHub**. Репозиторий должен быть выбран в рабочей среде.
3. Сохраните конфигурацию среды; если права старой сессии не обновляются,
   запустите новую задачу с подготовленной веткой/исходниками.

Обычный GitHub app в ChatGPT предназначен для чтения. Установка Connector сама
по себе не доказывает, что действующий токен получил все права: это проверяется
реальным push и запуском workflow. Пользователь не может добавить GitHub App
разрешение, которого разработчик App не запросил.

Настройка **Actions → General → Workflow permissions** относится к внутреннему
`GITHUB_TOKEN` workflow и не исправляет внешний токен, которым пушит рабочая
среда.

## Токен, если интеграции недостаточно

Создайте [fine-grained personal access token](https://github.com/settings/personal-access-tokens/new):
владелец `rw404`, только репозиторий `AndroidAPS`, ограниченный срок действия.
Минимальные права для этой задачи:

- **Contents → Read and write** — коммиты и ветка.
- **Workflows → Read and write** — изменение `.github/workflows/branch-ci.yml`.
- **Actions → Read and write** — запуск и чтение результатов Actions.

Доступ к чтению секретов Actions не нужен: существующий ключ используется только
внутри workflow. Право **Pull requests → Read and write** понадобится отдельно,
если потребуется создать PR.

Не отправляйте токен в чат, не записывайте его в репозиторий или командную строку.
Сохраните его через **Settings → Codex Cloud → Personal vault → Add → Network
secret** под именем `HEALFI_GH_TOKEN`, с доменами `api.github.com` и `github.com`.
Также подключите этот ключ в выбранной среде как **Network secret**, сохраните и
при необходимости перепубликуйте среду. Запись в личном vault сама по себе не
подключает секрет к уже запущенной задаче. Перед новой сессией сохраните
подготовленные исходники или скачайте полный patch.

После подключения права будут проверены отправкой отдельной ветки; слияние в
`master` для сборки не требуется. Существующий workflow в `master` заменяет APK
в Google Drive: запускайте подготовленный `Branch CI` на ref
`improve/quiet-pump-overview`, вариант **healfiRelease**.

## Источники

- [GitHub в ChatGPT](https://help.openai.com/en/articles/11145903-connecting-github-to-chatgpt).
- [Защищённые секреты облачной среды](https://developers.openai.com/codex/environments/cloud-environments#configure-environment-variables-and-network-secrets).
- [GitHub: fine-grained PAT](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens).

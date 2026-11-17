# Concurrent Booking System

Java Backend для бронирования мест на мероприятия и сравнения способов управления конкурентным доступом к PostgreSQL.

В проекте одна бизнес-операция реализована пятью способами: от намеренно уязвимого read-modify-write до условного SQL-обновления и транзакций `SERIALIZABLE`. Поведение проверяется интеграционными тестами на настоящей PostgreSQL и воспроизводимыми HTTP-сценариями k6.

## Возможности

- Просмотр мероприятий и их мест со статусами и ценами.
- Бронирование конкретного места от имени существующего пользователя.
- История активных и отменённых бронирований пользователя.
- Отмена с сохранением истории и освобождением места, если активных броней больше нет.
- Идемпотентная повторная отмена: повторный запрос владельца возвращает `204`.
- Пять переключаемых реализаций бронирования с одинаковым HTTP-контрактом.
- Отдельная обработка занятого места, конфликта версии, тайм-аута блокировки и ошибки сериализации.
- Конкурентные тесты, нагрузочный стенд, сбор результатов в CSV/JSON и графики PNG/SVG.

Основной бизнес-инвариант:

```text
Для одного места — не более одного ACTIVE-бронирования.
```

Защищённые стратегии должны сохранять этот инвариант. `naive` намеренно допускает его нарушение и используется как базовый пример гонки. В схеме нет уникального ограничения на активную бронь места: иначе оно скрыло бы поведение этой реализации. Это экспериментальный backend, а не готовый сервис продажи билетов.

## Стек

| Область | Инструменты |
|---|---|
| Backend | Java 21, Spring Boot 4.1.1, Spring MVC |
| Работа с данными | Spring Data JPA, Hibernate, PostgreSQL 17 |
| Схема БД | Flyway, Hibernate `ddl-auto=validate` |
| Сборка | Gradle Wrapper, Kotlin DSL |
| Тесты | JUnit Jupiter, Spring Boot Test, MockMvc, Testcontainers |
| Окружение | Docker, Docker Compose |
| Нагрузка | k6, JavaScript |
| Обработка результатов | Python 3, Matplotlib для графиков |

Зависимости Java управляются Spring Boot; версии определены в [build.gradle.kts](build.gradle.kts). Для учёта результатов достаточно стандартной библиотеки Python. Matplotlib нужен только для построения изображений.

## Архитектура и данные

Приложение построено как модульный монолит: один Spring Boot backend и одна PostgreSQL. Код разделён по бизнес-возможностям:

```text
src/main/java/com/example/concurrent_booking_system/
├── user/         # Пользователи
├── event/        # Мероприятия
├── seat/         # Места, чтение и конкурентные операции над ними
├── booking/      # Бронирование, стратегии, история и отмена
└── exception/    # Предметные ошибки и HTTP-обработчики
```

Контроллеры принимают HTTP-запросы и возвращают DTO. Бизнес-операции находятся в сервисах, доступ к данным — в репозиториях. Сущности JPA напрямую через API не выдаются.

Основные связи: `Event → Seat`, `User → Booking`, `Seat → Booking`. Статусы места: `FREE` / `BOOKED`; бронирования: `ACTIVE` / `CANCELLED`. Отмена сохраняет запись бронирования. Временного удержания места, оплаты и автоматического истечения броней нет.

### Стратегии бронирования

Стратегия выбирается на весь экземпляр приложения через `booking.strategy`, а не передаётся в каждом запросе.

| Значение | Механизм | Изоляция транзакции |
|---|---|---|
| `naive` | Чтение `FREE`, создание брони и обычное изменение места | `READ COMMITTED` |
| `optimistic` | `@Version`, обновление с проверкой прочитанной версии | `READ COMMITTED` |
| `pessimistic` | `PESSIMISTIC_WRITE`, блокировка строки перед проверкой статуса | `READ COMMITTED` |
| `atomic` | `UPDATE ... WHERE status = 'FREE'`, проверка количества обновлённых строк | `READ COMMITTED` |
| `serializable` | Проверка и запись под контролем PostgreSQL `SERIALIZABLE` | `SERIALIZABLE` |

Каждая попытка бронирования выполняется в отдельной транзакции (`REQUIRES_NEW`). Изменение места и создание бронирования фиксируются или откатываются вместе. Внешний `BookingService` выбирает стратегию и управляет ограниченными повторами для optimistic/serializable; каждая повторная попытка заново вызывает транзакционный метод через Spring proxy.

Для optimistic используется отдельное отображение таблицы `seats` — `OptimisticSeat` с `@Version`. Обычный `Seat` остаётся без версионирования, чтобы не добавлять неявную защиту в baseline. Эти варианты проверяются как отдельные стратегии; произвольное смешивание путей записи не является гарантией корректности.

Отмена использует блокировку места, условное изменение `ACTIVE → CANCELLED` и освобождение места только при отсутствии активных броней. При освобождении увеличивается версия строки, чтобы устаревший optimistic-снимок не мог незаметно занять её повторно.

Подробнее: [optimistic](docs/optimistic-locking.md), [pessimistic](docs/pessimistic-locking.md), [atomic](docs/atomic-booking.md), [serializable](docs/serializable-booking.md), [история и отмена](docs/booking-lifecycle.md).

## Локальный запуск

Нужны установленный JDK 21 и работающий Docker с Compose. Gradle отдельно устанавливать не требуется.

```bash
git clone https://github.com/altw8n/concurrent-booking-system.git
cd concurrent-booking-system
docker compose up -d --wait
./gradlew bootRun --args='--booking.strategy=atomic'
```

Приложение доступно по адресу `http://localhost:8080`, PostgreSQL — `localhost:5432`. Flyway применяет миграции при старте приложения; Hibernate проверяет соответствие схемы сущностям. `bootRun` остаётся работающим до остановки через `Ctrl+C`.

В `application.properties` по умолчанию указана `naive`; в примере выше она явно заменена на `atomic`.

Для запуска собранного приложения с JDK 21:

```bash
./gradlew bootJar
java -jar build/libs/concurrent-booking-system-0.0.1-SNAPSHOT.jar --booking.strategy=atomic
```

### Данные для проверки API

Обычная БД автоматически не заполняется демонстрационными данными. API создания пользователей, мероприятий и мест пока отсутствует. После старта приложения выполни в **другом терминале из корня проекта**:

```bash
docker compose exec -T postgres psql \
  -U booking_user -d booking_db -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

INSERT INTO users (email, password_hash)
VALUES ('demo@example.com', 'demo-placeholder')
ON CONFLICT (email) DO NOTHING;

SELECT id AS user_id FROM users WHERE email = 'demo@example.com';

WITH new_event AS (
    INSERT INTO events (name, description, start_time)
    VALUES ('Java Meetup', 'Demo event', now() + interval '1 day')
    RETURNING id
)
INSERT INTO seats (event_id, row_number, seat_number, status, price)
SELECT id, 'A', 1, 'FREE', 1000.00 FROM new_event
RETURNING event_id, id AS seat_id;

COMMIT;
SQL
```

Команда выведет `user_id`, `event_id` и `seat_id`. Повторный запуск добавит ещё одно мероприятие и место. В примерах ниже использованы ID `1`; если вывод содержит другие значения, замени их. `demo-placeholder` не является рабочим хешем пароля — аутентификация в приложении не реализована.

### Пример работы с API

```bash
# Мероприятия и места
curl http://localhost:8080/api/events
curl http://localhost:8080/api/events/1/seats

# Бронирование
curl -i -X POST http://localhost:8080/api/events/1/seats/1/book \
  -H 'Content-Type: application/json' \
  -d '{"userId": 1}'

# История пользователя
curl http://localhost:8080/api/users/1/bookings

# Отмена: подставь ID бронирования из ответа POST
curl -i -X DELETE 'http://localhost:8080/api/bookings/1?userId=1'
```

При успешном бронировании API возвращает `201` и DTO с `id`, `userId`, `seatId`, `status`, `createdAt`, `expiresAt`. Отмена возвращает `204` без тела.

Остановить PostgreSQL, сохранив её volume с данными:

```bash
docker compose down
```

## REST API

| Метод | Маршрут | Назначение |
|---|---|---|
| `GET` | `/api/events` | Список мероприятий |
| `GET` | `/api/events/{eventId}` | Одно мероприятие |
| `GET` | `/api/events/{eventId}/seats` | Места мероприятия со статусами |
| `POST` | `/api/events/{eventId}/seats/{seatId}/book` | Бронирование; тело `{"userId": 1}` |
| `GET` | `/api/users/{userId}/bookings` | Активные и отменённые бронирования пользователя |
| `DELETE` | `/api/bookings/{bookingId}?userId={userId}` | Отмена бронирования владельцем |

`userId` — передаваемый клиентом идентификатор существующего пользователя. Проверка владельца при отмене не заменяет аутентификацию: клиент может указать чужой ID. Авторизация и защита доступа находятся вне текущей реализации.

Предметные ошибки возвращаются в едином формате:

```json
{
  "code": "SEAT_ALREADY_BOOKED",
  "message": "..."
}
```

| HTTP | Коды ошибок |
|---|---|
| `400` | `INVALID_REQUEST` |
| `403` | `BOOKING_ACCESS_DENIED` |
| `404` | `EVENT_NOT_FOUND`, `SEAT_NOT_FOUND`, `USER_NOT_FOUND`, `BOOKING_NOT_FOUND` |
| `409` | `SEAT_ALREADY_BOOKED`, `OPTIMISTIC_LOCK_CONFLICT`, `SERIALIZATION_FAILURE` |
| `503` | `PESSIMISTIC_LOCK_TIMEOUT`, `BOOKING_CANCELLATION_TIMEOUT` |

## Конфигурация

| Параметр | По умолчанию | Назначение |
|---|---|---|
| `booking.strategy` | `naive` | Реализация бронирования |
| `booking.optimistic.max-retries` | `0` | Дополнительные попытки после конфликта версии |
| `booking.serializable.max-retries` | `0` | Дополнительные полные транзакции после SQLSTATE `40001` |
| `booking.pessimistic.lock-timeout-ms` | `2000` | Лимит ожидания блокировки в pessimistic |
| `booking.cancellation.lock-timeout-ms` | `2000` | Лимит ожидания блокировки при отмене |

`max-retries=0` означает одну исходную попытку без повторов. Повтор не гарантирует успех: в новой транзакции место уже может оказаться занятым. HTTP timeout и тайм-аут блокировки — разные ограничения.

Пример включения ограниченных повторов:

```bash
./gradlew bootRun --args='--booking.strategy=serializable --booking.serializable.max-retries=2'
```

Это максимум три попытки: одна исходная и две дополнительные. Данные подключения задаются стандартными параметрами Spring, например `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`. Учётные данные в Compose предназначены для локального окружения.

## Тестирование

Для интеграционных тестов нужен доступ к Docker. Testcontainers сам создаёт PostgreSQL; вручную запускать обычную Compose-БД для тестов не требуется.

```bash
./gradlew test --rerun-tasks
```

HTML-отчёт: `build/reports/tests/test/index.html`.

Набор содержит unit/MockMvc-проверки и PostgreSQL-интеграционные тесты: валидацию, выбор стратегии, откат изменений, конкурентную гонку, обнаружение конфликтов, ожидание блокировок, новые транзакции при retry и жизненный цикл бронирования. Конкурентные тесты синхронизируют потоки барьерами и latch; naive-тест считается успешным, когда воспроизведено нарушение инварианта.

Проверки вспомогательных скриптов:

```bash
python -m unittest discover -s experiments/tests -v
node load-tests/pilot-model.test.mjs
```

Node нужен только для тестов JavaScript-функций. Сам сценарий нагрузки выполняет k6.

## Нагрузка и анализ результатов

Для запуска нужны k6, Python 3, JDK 21 и Docker с Compose:

```bash
python experiments/run_pilot.py --strategy atomic
```

Оркестратор проверяет Java-тесты и сборку, поднимает отдельную PostgreSQL на `5433` и приложение на `8090`, создаёт данные, выполняет прогрев и измеряемые волны k6. Затем сохраняет ответы/метрики, выгружает состояние БД и удаляет только собственные контейнеры и volume. Обычная локальная БД на `5432` не используется.

Параметры клиентов и конкуренции можно менять независимо от выбора стратегии:

```bash
python experiments/run_pilot.py \
  --strategy optimistic \
  --vus 30 \
  --seats-per-wave 6 \
  --measured-waves 10 \
  --output-root experiments/raw-results/demo-series
```

Каждая волна использует новый набор свободных мест: это позволяет снова создавать конкуренцию за доступный ресурс, а не измерять только ответы «уже занято». Клиентские и серверные retry в этом стенде отключены.

Сбор таблиц и построение графиков:

```bash
python experiments/collect_results.py \
  --input experiments/raw-results/demo-series \
  --output experiments/processed-results/demo-series

python -m venv .venv
.venv/bin/python -m pip install -r experiments/requirements-plotting.txt

.venv/bin/python experiments/plot_results.py \
  --input experiments/processed-results/demo-series
```

Сборщик работает и с одним запуском; графики по умолчанию требуют по три запуска каждой из пяти стратегий для каждого обнаруженного сценария, выполненных в совместимых условиях. Поэтому последняя команда завершится ошибкой, пока такая серия не собрана. Полная инструкция: [load-tests/README.md](load-tests/README.md).


## Границы проекта

Backend сосредоточен на транзакциях и конкурентной работе с данными. Регистрация, аутентификация, оплата, frontend, пагинация и административный API ещё не реализованы. Метрики CPU/RAM во времени, Actuator, Prometheus и Grafana не подключены. Исследование непрерывной предельной нагрузки и влияние включённых retries требуют отдельных сценариев.

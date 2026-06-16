# FsDemo - Backend for FFmpeg Wrapper (Учебный проект)

**FsDemo** — это учебный бэкенд-сервис, построенный на **Spring Boot**, который предоставляет REST API для обработки видеофайлов с помощью **FFmpeg**.

---

## Основные возможности

- **Загрузка и управление видео** — API для загрузки, хранения и получения видео.
- **Обработка видео через FFmpeg** — асинхронное выполнение задач (обрезка, удаление звуковой дорожки и т. п.) с использованием библиотеки `net.bramp.ffmpeg`.
- **SSE-уведомления о прогрессе** — клиенты могут подписываться на события обработки видео в реальном времени через Server-Sent Events.
- **Аутентификация и безопасность** — JWT-авторизация с использованием `jjwt` и Spring Security.
- **Документация API** — встроенная OpenAPI-документация через `springdoc-openapi`.
- **Модульная архитектура** — чёткое разделение на слои: `controller`, `service`, `repository`, `domain`, `config`, `security`, `listeners`.
- **Тестирование** — покрытие unit- и интеграционными тестами.

---

## Технологический стек

| Компонент | Технология |
|-----------|------------|
| **Язык** | Java 21 |
| **Фреймворк** | Spring Boot 3.4.4 |
| **База данных** | MariaDB (JPA / Hibernate) |
| **Безопасность** | Spring Security + JWT |
| **FFmpeg** | net.bramp.ffmpeg 0.8.0 |
| **Документация API** | SpringDoc OpenAPI 2.8.6 |
| **Сборка** | Gradle |
| **Тестирование** | JUnit 5, Spring Boot Test, H2 (для тестов) |

---

## Структура проекта (ключевые пакеты)

```
src/main/java/com/example/fsdemo/
├── config/          - Конфигурационные классы (Security, FFmpeg и др.)
├── domain/          - Сущности
├── events/          - SSE-события и обработчики
├── exceptions/      - Кастомные исключения
├── listeners/       - Слушатели событий (в т.ч. прогресс видео)
├── repository/      - JPA-репозитории
├── security/        - JWT-фильтры и настройки аутентификации
├── service/         - Бизнес-логика (в т.ч. VideoProcessingService)
├── web/             - REST-контроллеры и DTO
└── FsDemoApplication.java - Точка входа
```

---

## Запуск проекта

### Требования
- Java 21
- Gradle
- MariaDB
- Установленный **FFmpeg** в системе

### Шаги

1. Клонировать проект:
   ```bash
   git clone https://github.com/constant1neB/FsDemo.git
   cd FsDemo
   ```

2. Собрать проект:
   ```bash
   ./gradlew build
   ```

3. Запустить приложение:
   ```bash
   ./gradlew bootRun
   ```

После запуска API будет доступно по адресу: `http://localhost:8080`  
Документация OpenAPI: `http://localhost:8080/swagger-ui.html`

---

## Тестирование

```bash
# Unit-тесты
./gradlew test

# Интеграционные тесты
./gradlew integrationTest

# Все тесты
./gradlew check
```

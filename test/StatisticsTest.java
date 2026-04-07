import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;


class LogAnalyzerTest {

    private static int passed = 0;
    private static int failed = 0;

    static void main(String[] args) {
        System.out.println("=== Запуск тестов ===\n");

        // Тесты PercentileSketch
        testPercentileSketchEmpty();
        testPercentileSketchSingleValue();
        testPercentileSketchLargeValues();
        testPercentileSketchP50();
        testPercentileSketchMemoryConstant();

        // Тесты Statistics — парсинг строк
        testLogLineValidLine();
        testLogLineInvalidLine();
        testLogLinePostRequest();
        testLogLineMultipleLines();
        testLogLineDateFiltering();
        testLogLineDateFilteringFromOnly();
        testLogLineDateFilteringToOnly();

        // Тесты Statistics — dateCorrect
        testDateCorrectBothNull();
        testDateCorrectFromOnly();
        testDateCorrectToOnly();
        testDateCorrectBothSet();
        testDateCorrectOutOfRange();

        // Тесты Statistics — средние значения
        testAverageEmpty();
        testAverageCalculation();

        // Тесты Statistics — ресурсы
        testResourceCounting();

        // Тесты Statistics — коды ответа
        testResponseCodeCounting();

        // Тесты Statistics — распределение по датам
        testRequestsPerDate();

        // Тесты Statistics — чтение файлов
        testReadFilesFromRealFile();

        // Тесты Statistics — сохранение в JSON
        testSaveToJson();

        // Тесты Statistics — сохранение в Markdown
        testSaveToMarkdown();

        // Тесты Statistics — перцентиль через реальные данные
        testPercentileWithLogLines();

        System.out.println("\n=== Результаты ===");
        System.out.println("Пройдено: " + passed);
        System.out.println("Провалено: " + failed);
        System.out.println("Всего: " + (passed + failed));

        if (failed > 0) {
            System.exit(1);
        }
    }


    private static void runTest(String name, Runnable test) {
        try {
            test.run();
            System.out.println(name);
            passed++;
        } catch (AssertionError | Exception e) {
            System.out.println(name + " — " + e.getMessage());
            failed++;
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError(message + ": ожидалось [" + expected + "], получено [" + actual + "]");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertApprox(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(message + ": ожидалось ~" + expected + " (±" + tolerance + "), получено " + actual);
        }
    }

    //Тесты Percentile

    static void testPercentileSketchEmpty() {
        runTest("PercentileSketch: пустой возвращает 0", () -> {
            Percentile sketch = new Percentile();
            assertApprox(0.0, sketch.getPercentile(0.95), 0.01,
                    "Пустой должен возвращать 0");
        });
    }

    static void testPercentileSketchSingleValue() {
        runTest("PercentileSketch: одно значение", () -> {
            Percentile sketch = new Percentile();
            sketch.add(1000);
            double p95 = sketch.getPercentile(0.95);
            // Для одного значения перцентиль должен быть примерно равен этому значению
            assertApprox(1000, p95, 1500, // допуск из-за ширины бакета
                    "Одно значение 1000 — p95 должен быть около 1000");
        });
    }

    static void testPercentileSketchLargeValues() {
        runTest("PercentileSketch: большие значения", () -> {
            Percentile sketch = new Percentile();
            // 95 значений по 100, 5 значений по 5000
            for (int i = 0; i < 95; i++) {
                sketch.add(100);
            }
            for (int i = 0; i < 5; i++) {
                sketch.add(5000);
            }
            double p95 = sketch.getPercentile(0.95);
            // p95 должен быть около 100 (95% значений = 100)
            assertTrue(p95 < 1000,
                    "p95 для 95x100 + 5x5000 должен быть около 100, получено: " + p95);
        });
    }

    static void testPercentileSketchP50() {
        runTest("PercentileSketch: медиана (p50) для равномерного распределения", () -> {
            Percentile sketch = new Percentile();
            for (int i = 0; i < 1000; i++) {
                sketch.add(i);
            }
            double p50 = sketch.getPercentile(0.50);
            assertApprox(500, p50, 100,
                    "Медиана для 0..999 должна быть около 500");
        });
    }

    static void testPercentileSketchMemoryConstant() {
        runTest("PercentileSketch: память O(1) — добавляем миллион элементов", () -> {
            Percentile sketch = new Percentile();
            //добавляем 1 миллион элементов — память не должна расти
            for (int i = 0; i < 1_000_000; i++) {
                sketch.add(i % 10000);
            }
            assertEquals(1_000_000L, sketch.getTotalCount(),
                    "Количество элементов должно быть 1000000");
            double p95 = sketch.getPercentile(0.95);
            assertTrue(p95 > 0, "p95 должен быть больше 0 для миллиона элементов");
        });
    }


    //Тесты Statistics — парсинг строк

    static void testLogLineValidLine() {
        runTest("logLine: корректная строка парсится правильно", () -> {
            Statistics stats = new Statistics();
            String line = "93.180.71.3 - - [17/May/2015:08:05:32 +0000] \"GET /downloads/product_1 HTTP/1.1\" 304 0 \"-\" \"Debian APT-HTTP/1.3 (0.8.16~exp12ubuntu10.21)\"";
            Statistics.logLine(line, stats, null, null);

            assertEquals(1L, stats.allRequests, "Должен быть 1 запрос");
            assertEquals(0L, stats.sumSize, "Размер должен быть 0");
            assertEquals(0L, stats.maxSize, "Максимальный размер должен быть 0");
            assertTrue(stats.resources.containsKey("/downloads/product_1"),
                    "Ресурс /downloads/product_1 должен быть в map");
            assertTrue(stats.periodicityResponseCodes.containsKey(304),
                    "Код 304 должен быть в map");
        });
    }

    static void testLogLineInvalidLine() {
        runTest("logLine: некорректная строка пропускается", () -> {
            Statistics stats = new Statistics();
            String line = "это какая-то ерунда а не лог";
            Statistics.logLine(line, stats, null, null);

            assertEquals(0L, stats.allRequests, "Некорректная строка не должна увеличивать счётчик");
        });
    }

    static void testLogLinePostRequest() {
        runTest("logLine: POST запрос парсится", () -> {
            Statistics stats = new Statistics();
            String line = "10.0.0.1 - user [01/Jan/2024:12:00:00 +0000] \"POST /api/data HTTP/1.1\" 200 512 \"-\" \"curl/7.68\"";
            Statistics.logLine(line, stats, null, null);

            assertEquals(1L, stats.allRequests, "POST запрос должен быть обработан");
            assertTrue(stats.resources.containsKey("/api/data"),
                    "Ресурс /api/data должен быть в map");
            assertEquals(512L, stats.sumSize, "Размер должен быть 512");
        });
    }

    static void testLogLineMultipleLines() {
        runTest("logLine: несколько строк — правильный подсчёт", () -> {
            Statistics stats = new Statistics();
            String line1 = "1.1.1.1 - - [17/May/2015:08:05:32 +0000] \"GET /page1 HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";
            String line2 = "2.2.2.2 - - [17/May/2015:08:05:33 +0000] \"GET /page2 HTTP/1.1\" 404 200 \"-\" \"Mozilla\"";
            String line3 = "3.3.3.3 - - [17/May/2015:08:05:34 +0000] \"GET /page1 HTTP/1.1\" 200 300 \"-\" \"Mozilla\"";

            Statistics.logLine(line1, stats, null, null);
            Statistics.logLine(line2, stats, null, null);
            Statistics.logLine(line3, stats, null, null);

            assertEquals(3L, stats.allRequests, "Должно быть 3 запроса");
            assertEquals(600L, stats.sumSize, "Сумма размеров: 100+200+300=600");
            assertEquals(300L, stats.maxSize, "Максимальный размер: 300");
            assertEquals(2, stats.resources.get("/page1"), "/page1 — 2 раза");
            assertEquals(1, stats.resources.get("/page2"), "/page2 — 1 раз");
        });
    }


    //тесты Statistics — фильтрую по датам

    static void testLogLineDateFiltering() {
        runTest("logLine: фильтрация по диапазону дат", () -> {
            Statistics stats = new Statistics();
            LocalDate from = LocalDate.of(2015, 5, 17);
            LocalDate to = LocalDate.of(2015, 5, 17);

            String lineInRange = "1.1.1.1 - - [17/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";
            String lineOutOfRange = "1.1.1.1 - - [18/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";

            Statistics.logLine(lineInRange, stats, from, to);
            Statistics.logLine(lineOutOfRange, stats, from, to);

            assertEquals(1L, stats.allRequests, "Только одна строка попадает в диапазон дат");
        });
    }

    static void testLogLineDateFilteringFromOnly() {
        runTest("logLine: фильтрация only from", () -> {
            Statistics stats = new Statistics();
            LocalDate from = LocalDate.of(2015, 5, 18);

            String lineBefore = "1.1.1.1 - - [17/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";
            String lineExact = "1.1.1.1 - - [18/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";
            String lineAfter = "1.1.1.1 - - [19/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";

            Statistics.logLine(lineBefore, stats, from, null);
            Statistics.logLine(lineExact, stats, from, null);
            Statistics.logLine(lineAfter, stats, from, null);

            assertEquals(2L, stats.allRequests, "2 строки >= from");
        });
    }

    static void testLogLineDateFilteringToOnly() {
        runTest("logLine: фильтрация only to", () -> {
            Statistics stats = new Statistics();
            LocalDate to = LocalDate.of(2015, 5, 17);

            String lineExact = "1.1.1.1 - - [17/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";
            String lineAfter = "1.1.1.1 - - [18/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";

            Statistics.logLine(lineExact, stats, null, to);
            Statistics.logLine(lineAfter, stats, null, to);

            assertEquals(1L, stats.allRequests, "1 строка <= to");
        });
    }


    //Тесты dateCorrect

    static void testDateCorrectBothNull() {
        runTest("dateCorrect: обе даты null — всегда true", () -> assertTrue(Statistics.dateCorrect(LocalDate.of(2020, 1, 1), null, null),
                "Без фильтров любая дата должна проходить"));
    }

    static void testDateCorrectFromOnly() {
        runTest("dateCorrect: только from", () -> {
            LocalDate from = LocalDate.of(2020, 6, 1);
            assertTrue(Statistics.dateCorrect(LocalDate.of(2020, 6, 1), from, null), "Равная from — true");
            assertTrue(Statistics.dateCorrect(LocalDate.of(2020, 7, 1), from, null), "После from — true");
            assertFalse(Statistics.dateCorrect(LocalDate.of(2020, 5, 1), from, null), "До from — false");
        });
    }

    static void testDateCorrectToOnly() {
        runTest("dateCorrect: только to", () -> {
            LocalDate to = LocalDate.of(2020, 6, 1);
            assertTrue(Statistics.dateCorrect(LocalDate.of(2020, 6, 1), null, to), "Равная to — true");
            assertTrue(Statistics.dateCorrect(LocalDate.of(2020, 5, 1), null, to), "До to — true");
            assertFalse(Statistics.dateCorrect(LocalDate.of(2020, 7, 1), null, to), "После to — false");
        });
    }

    static void testDateCorrectBothSet() {
        runTest("dateCorrect: обе даты заданы — внутри диапазона", () -> {
            LocalDate from = LocalDate.of(2020, 1, 1);
            LocalDate to = LocalDate.of(2020, 12, 31);
            assertTrue(Statistics.dateCorrect(LocalDate.of(2020, 6, 15), from, to), "Середина — true");
            assertTrue(Statistics.dateCorrect(LocalDate.of(2020, 1, 1), from, to), "Начало — true");
            assertTrue(Statistics.dateCorrect(LocalDate.of(2020, 12, 31), from, to), "Конец — true");
        });
    }

    static void testDateCorrectOutOfRange() {
        runTest("dateCorrect: обе даты заданы — вне диапазона", () -> {
            LocalDate from = LocalDate.of(2020, 1, 1);
            LocalDate to = LocalDate.of(2020, 12, 31);
            assertFalse(Statistics.dateCorrect(LocalDate.of(2019, 12, 31), from, to), "До диапазона — false");
            assertFalse(Statistics.dateCorrect(LocalDate.of(2021, 1, 1), from, to), "После диапазона — false");
        });
    }


    //Тесты средних значений

    static void testAverageEmpty() {
        runTest("getAverage: пустая статистика — 0", () -> {
            Statistics stats = new Statistics();
            assertApprox(0.0, stats.getAverage(), 0.001, "Среднее для пустой статистики должно быть 0");
        });
    }

    static void testAverageCalculation() {
        runTest("getAverage: расчёт среднего", () -> {
            Statistics stats = new Statistics();
            stats.allRequests = 4;
            stats.sumSize = 1000;
            assertApprox(250.0, stats.getAverage(), 0.001, "1000/4 = 250");
        });
    }


    //Тесты подсчёта ресурсов

    static void testResourceCounting() {
        runTest("Подсчёт ресурсов: top-10 сортировка", () -> {
            Statistics stats = new Statistics();
            // Добавляем 12 разных ресурсов
            for (int i = 0; i < 12; i++) {
                String resource = "/resource_" + i;
                for (int j = 0; j <= i; j++) {
                    stats.resources.merge(resource, 1, Integer::sum);
                }
            }
            // /resource_11 = 12, /resource_10 = 11, ..., /resource_0 = 1
            var top10 = stats.resources.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .toList();

            assertEquals(10, top10.size(), "Должно быть 10 ресурсов в топе");
            assertEquals("/resource_11", top10.getFirst().getKey(), "Первый — resource_11");
            assertEquals(12, top10.getFirst().getValue(), "resource_11 = 12 запросов");
        });
    }


    //Тесты подсчёта кодов ответа

    static void testResponseCodeCounting() {
        runTest("Подсчёт кодов ответа", () -> {
            Statistics stats = new Statistics();
            String line200 = "1.1.1.1 - - [17/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";
            String line404 = "1.1.1.1 - - [17/May/2015:08:05:33 +0000] \"GET /missing HTTP/1.1\" 404 0 \"-\" \"Mozilla\"";

            Statistics.logLine(line200, stats, null, null);
            Statistics.logLine(line200, stats, null, null);
            Statistics.logLine(line404, stats, null, null);

            assertEquals(2, stats.periodicityResponseCodes.get(200), "200 — 2 раза");
            assertEquals(1, stats.periodicityResponseCodes.get(404), "404 — 1 раз");
        });
    }


    //Тесты распределения по датам

    static void testRequestsPerDate() {
        runTest("Распределение запросов по датам", () -> {
            Statistics stats = new Statistics();
            String line1 = "1.1.1.1 - - [17/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";
            String line2 = "1.1.1.1 - - [17/May/2015:09:05:32 +0000] \"GET /page HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";
            String line3 = "1.1.1.1 - - [18/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 100 \"-\" \"Mozilla\"";

            Statistics.logLine(line1, stats, null, null);
            Statistics.logLine(line2, stats, null, null);
            Statistics.logLine(line3, stats, null, null);

            LocalDate may17 = LocalDate.of(2015, 5, 17);
            LocalDate may18 = LocalDate.of(2015, 5, 18);

            assertEquals(2, stats.requestsPerDate.get(may17), "17 мая — 2 запроса");
            assertEquals(1, stats.requestsPerDate.get(may18), "18 мая — 1 запрос");
        });
    }


    //Тесты чтения файлов

    static void testReadFilesFromRealFile() {
        runTest("readFiles: чтение из временного файла", () -> {
            try {
                // Создаём временный файл с логами
                Path tempFile = Files.createTempFile("test_log_", ".log");
                String content = """
                        93.180.71.3 - - [17/May/2015:08:05:32 +0000] "GET /downloads/product_1 HTTP/1.1" 304 0 "-" "Debian APT-HTTP/1.3"
                        93.180.71.3 - - [17/May/2015:08:05:23 +0000] "GET /downloads/product_2 HTTP/1.1" 200 490 "-" "Debian APT-HTTP/1.3"
                        некорректная строка
                        80.91.33.133 - - [17/May/2015:08:05:24 +0000] "GET /downloads/product_1 HTTP/1.1" 304 0 "-" "Debian APT-HTTP/1.3"
                        """;
                Files.writeString(tempFile, content);

                Statistics stats = Statistics.readFiles(List.of(tempFile), null, null);

                assertEquals(3L, stats.allRequests, "3 корректные строки из 4");
                assertEquals(490L, stats.sumSize, "Сумма размеров: 0+490+0=490");
                assertEquals(490L, stats.maxSize, "Максимальный размер: 490");
                assertEquals(2, stats.resources.get("/downloads/product_1"), "product_1 — 2 раза");
                assertEquals(1, stats.resources.get("/downloads/product_2"), "product_2 — 1 раз");

                // Удаляем временный файл
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                throw new RuntimeException("Ошибка при создании временного файла: " + e.getMessage());
            }
        });
    }


    //Тесты сохранения

    static void testSaveToJson() {
        runTest("saveToJson: файл создаётся и содержит нужные поля", () -> {
            try {
                Statistics stats = new Statistics();
                String line = "1.1.1.1 - - [17/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 500 \"-\" \"Mozilla\"";
                Statistics.logLine(line, stats, null, null);

                Path tempOutput = Files.createTempFile("test_output_", ".json");
                Files.deleteIfExists(tempOutput); // saveToJson ожидает что файла нет

                Path tempInput = Files.createTempFile("input_", ".log");
                stats.saveToJson(tempOutput.toString(), List.of(tempInput));

                String json = Files.readString(tempOutput);
                assertTrue(json.contains("\"totalRequestsCount\": 1"), "JSON должен содержать totalRequestsCount: 1");
                assertTrue(json.contains("\"max\": 500"), "JSON должен содержать max: 500");
                assertTrue(json.contains("\"resource\": \"/page\""), "JSON должен содержать ресурс /page");
                assertTrue(json.contains("\"code\": 200"), "JSON должен содержать код 200");

                Files.deleteIfExists(tempOutput);
                Files.deleteIfExists(tempInput);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }


    static void testSaveToMarkdown() {
        System.out.print("Тест saveToMarkdown: ");

        try {
            //создаем статистику с одним запросом
            Statistics stats = new Statistics();
            String line = "1.1.1.1 - - [17/May/2015:08:05:32 +0000] \"GET /test HTTP/1.1\" 200 500";
            Statistics.logLine(line, stats, null, null);

            //создаем временный файл
            Path tempFile = Files.createTempFile("test_", ".md");
            Files.deleteIfExists(tempFile); // чтобы файла не было

            //сохраняем
            stats.saveToMarkdown(tempFile.toString(), List.of(Path.of("test.log")), null, null);

            //проверяем что файл создался
            if (Files.exists(tempFile)) {
                String content = Files.readString(tempFile);
                if (content.contains("Количество запросов | 1") &&
                        content.contains("200 | OK | 1")) {
                    System.out.println("OK");
                } else {
                    System.out.println("FAILED - неправильное содержимое");
                }
            } else {
                System.out.println("FAILED - файл не создан");
            }

            //удаляем
            Files.deleteIfExists(tempFile);

        } catch (Exception e) {
            System.out.println("ОШИБКА: " + e.getMessage());
        }
    }



    static void testPercentileWithLogLines() {
        System.out.print("Тест перцентиля через логи: ");

        try {
            //создаем статистику
            Statistics stats = new Statistics();

            //добавляем 95 запросов с размером 100 байт
            for (int i = 0; i < 95; i++) {
                String line = "1.1.1.1 - - [17/May/2015:08:05:32 +0000] \"GET /page HTTP/1.1\" 200 100";
                Statistics.logLine(line, stats, null, null);
            }

            //добавляем 5 запросов с размером 5000 байт
            for (int i = 0; i < 5; i++) {
                String line = "1.1.1.1 - - [17/May/2015:08:05:33 +0000] \"GET /page HTTP/1.1\" 200 5000";
                Statistics.logLine(line, stats, null, null);
            }

            //проверяем общее количество
            if (stats.allRequests != 100) {
                System.out.println("\n  Ошибка: всего запросов " + stats.allRequests + " (должно быть 100)");
                return;
            }

            //проверяем сумму размеров (95*100 + 5*5000 = 9500 + 25000 = 34500)
            if (stats.sumSize != 34500) {
                System.out.println("\n  Ошибка: сумма размеров " + stats.sumSize + " (должно быть 34500)");
                return;
            }

            //проверяем максимальный размер
            if (stats.maxSize != 5000) {
                System.out.println("\n  Ошибка: макс размер " + stats.maxSize + " (должно быть 5000)");
                return;
            }

            //проверяем среднее
            double avg = stats.getAverage();
            double expectedAvg = 34500.0 / 100; // 345.0
            if (Math.abs(avg - expectedAvg) > 0.1) {
                System.out.println("\n  Ошибка: среднее " + avg + " (должно быть " + expectedAvg + ")");
                return;
            }

            //проверяем что метод getPercentile() существует и возвращает число
            double p95 = stats.getPercentile();
            System.out.println("OK (p95=" + p95 + ")");

        } catch (Exception e) {
            System.out.println("ОШИБКА: " + e.getMessage());
        }
    }
}



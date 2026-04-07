


import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class Statistics {
    long allRequests = 0;
    long sumSize = 0;
    long maxSize = 0;
    Map<Integer, Integer> periodicityResponseCodes = new HashMap<>();
    Map<String, Integer> resources = new HashMap<>();
    Map<LocalDate, Integer> requestsPerDate = new HashMap<>();
    Percentile percentileCalculator = new Percentile(); // Добавлен калькулятор перцентилей

    double getAverage() {
        return allRequests == 0 ? 0 : (double) sumSize / allRequests;
    }

    double getPercentile() {
        return percentileCalculator.getPercentile(0.95); // 95-й перцентиль
    }

    public static Statistics readFiles(List<Path> files, LocalDate fromDate, LocalDate toDate) {
        Statistics combinedStatistics = new Statistics();
        int allLines = 0;

        for (Path file : files) {
            System.out.println("Чтение файла: " + file.getFileName());

            try (BufferedReader br = Files.newBufferedReader(file)) {
                String line;
                int fileLines = 0;

                while ((line = br.readLine()) != null) {
                    fileLines++;
                    logLine(line, combinedStatistics, fromDate, toDate);
                }

                System.out.println("  Обработано строк: " + fileLines);
                allLines += fileLines;

            } catch (IOException e) {
                System.err.println("Ошибка при чтении файла " + file + ": " + e.getMessage());
            }
        }

        System.out.println("Всего обработано строк: " + allLines);
        System.out.println("Успешно обработано запросов: " + combinedStatistics.allRequests);

        return combinedStatistics;
    }

    public static void logLine(String line, Statistics statistics, LocalDate fromDate, LocalDate toDate) {
        //лог из задания
        //93.180.71.3 - - [17/May/2015:08:05:32 +0000] "GET /downloads/product_1 HTTP/1.1" 304 0
        // "-" "Debian APT-HTTP/1.3 (0.8.16~exp12ubuntu10.21)"

        try {
            int indexDateStart = line.indexOf('[') + 1;
            int indexDateEnd = line.indexOf(':', indexDateStart);
            String stringDate = line.substring(indexDateStart, indexDateEnd);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MMM/yyyy", Locale.ENGLISH);
            LocalDate logDate = LocalDate.parse(stringDate, formatter);
            if (!dateCorrect(logDate, fromDate, toDate)) {
                return;
            }

            int indexResourceStart = line.indexOf("GET ") + 4;
            if (indexResourceStart < 4) {
                indexResourceStart = line.indexOf("POST ") + 5;
            }
            if (indexResourceStart < 5) {
                indexResourceStart = line.indexOf("PUT ") + 4;
            }
            int indexResourceEnd = line.indexOf(" HTTP", indexResourceStart);
            String stringResource = line.substring(indexResourceStart, indexResourceEnd);

            int afterRequest = line.indexOf("\" ", indexResourceEnd) + 2;

            String remaining = line.substring(afterRequest);
            String[] parts = remaining.split(" ");

            int statusCode = Integer.parseInt(parts[0]);
            int size = Integer.parseInt(parts[1]);

            statistics.allRequests++;
            statistics.maxSize = Math.max(statistics.maxSize, size);
            statistics.sumSize += size;
            statistics.periodicityResponseCodes.merge(statusCode, 1, Integer::sum);
            statistics.resources.merge(stringResource, 1, Integer::sum);
            statistics.requestsPerDate.merge(logDate, 1, Integer::sum);
            statistics.percentileCalculator.add(size); // Добавляем размер для расчета перцентиля

        } catch (Exception e) {
            System.err.println("Не получилось обработать строку: " + line);
        }
    }

    public static boolean dateCorrect(LocalDate logDate, LocalDate fromDate, LocalDate toDate) {
        //from != null && to != null
        if (fromDate != null && toDate != null) {
            return !logDate.isBefore(fromDate) && !logDate.isAfter(toDate);
        }
        //from != null && to == null
        if (fromDate != null && toDate == null) {
            return !logDate.isBefore(fromDate);
        }
        //from == null && to != null
        if (fromDate == null && toDate != null) {
            return !logDate.isAfter(toDate);
        }
        //from == null && to == null
        return true;
    }

    public void saveToJson(String outputPath, List<Path> files) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");

            //files - массив с именами файлов
            json.append("  \"files\": [\n");
            for (int i = 0; i < files.size(); i++) {
                json.append("    \"").append(escapeJson(files.get(i).getFileName().toString())).append("\"");
                if (i < files.size() - 1) {
                    json.append(",");
                }
                json.append("\n");
            }
            json.append("  ],\n");

            //totalRequestsCount
            json.append("  \"totalRequestsCount\": ").append(allRequests).append(",\n");

            //responseSizeInBytes
            json.append("  \"responseSizeInBytes\": {\n");
            json.append("    \"average\": ").append(String.format(Locale.US, "%.2f", getAverage())).append(",\n");
            json.append("    \"max\": ").append(maxSize).append(",\n");
            json.append("    \"p95\": ").append(String.format(Locale.US, "%.2f", getPercentile())).append("\n");
            json.append("  },\n");

            //resources (топ-10)
            json.append("  \"resources\": [\n");
            if (resources != null && !resources.isEmpty()) {
                var topResources = resources.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(10)
                        .toList();

                for (int i = 0; i < topResources.size(); i++) {
                    var entry = topResources.get(i);
                    json.append("    {\n");
                    json.append("      \"resource\": \"").append(escapeJson(entry.getKey())).append("\",\n");
                    json.append("      \"totalRequestsCount\": ").append(entry.getValue()).append("\n");
                    json.append("    }");
                    if (i < topResources.size() - 1) {
                        json.append(",");
                    }
                    json.append("\n");
                }
            }
            json.append("  ],\n");

            //responseCodes
            json.append("  \"responseCodes\": [\n");
            if (periodicityResponseCodes != null && !periodicityResponseCodes.isEmpty()) {
                var codes = periodicityResponseCodes.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList();

                for (int i = 0; i < codes.size(); i++) {
                    var entry = codes.get(i);
                    json.append("    {\n");
                    json.append("      \"code\": ").append(entry.getKey()).append(",\n");
                    json.append("      \"totalResponsesCount\": ").append(entry.getValue()).append("\n");
                    json.append("    }");
                    if (i < codes.size() - 1) {
                        json.append(",");
                    }
                    json.append("\n");
                }
            }
            json.append("  ],\n");

            //requestsPerDate
            json.append("  \"requestsPerDate\": [\n");
            if (requestsPerDate != null && !requestsPerDate.isEmpty() && allRequests > 0) {
                var dates = requestsPerDate.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList();

                for (int i = 0; i < dates.size(); i++) {
                    var entry = dates.get(i);
                    LocalDate date = entry.getKey();
                    int count = entry.getValue();
                    double percentage = (count * 100.0) / allRequests;

                    json.append("    {\n");
                    json.append("      \"date\": \"").append(date).append("\",\n");
                    json.append("      \"weekday\": \"").append(date.getDayOfWeek()).append("\",\n");
                    json.append("      \"totalRequestsCount\": ").append(count).append(",\n");
                    json.append("      \"totalRequestsPercentage\": ").append(String.format(Locale.US, "%.2f", percentage)).append("\n");
                    json.append("    }");
                    if (i < dates.size() - 1) {
                        json.append(",");
                    }
                    json.append("\n");
                }
            }
            json.append("  ]\n");
            json.append("}\n");

            Files.writeString(Paths.get(outputPath), json.toString(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("Результат сохранен в " + outputPath);

        } catch (IOException e) {
            throw new RuntimeException("Ошибка при сохранении JSON: " + e.getMessage());
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public void saveToMarkdown(String outputPath, List<Path> files, LocalDate fromDate, LocalDate toDate) throws IOException {
        StringBuilder md = new StringBuilder();

        //заголовок
        md.append("# Отчет анализа логов NGINX\n\n");

        //общая информация
        md.append("## Общая информация\n\n");
        md.append("| Метрика | Значение |\n");
        md.append("|:--------|---------:|\n");

        //файлы
        String fileNames = files.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.joining(", "));
        md.append("| Файл(-ы) | ").append(fileNames).append(" |\n");

        //даты
        md.append("| Начальная дата | ").append(fromDate == null ? "-" : fromDate).append(" |\n");
        md.append("| Конечная дата | ").append(toDate == null ? "-" : toDate).append(" |\n");

        //статистика запросов
        md.append("| Количество запросов | ").append(String.format("%,d", allRequests).replace(',', '_')).append(" |\n");
        md.append("| Средний размер ответа | ").append(String.format("%.2f", getAverage())).append(" b |\n");
        md.append("| Максимальный размер ответа | ").append(maxSize).append(" b |\n");
        md.append("| 95p размера ответа | ").append(String.format("%.2f", getPercentile())).append(" b |\n\n");

        //запрашиваемые ресурсы
        md.append("## Запрашиваемые ресурсы (топ-10)\n\n");
        md.append("| Ресурс | Количество |\n");
        md.append("|:-------|-----------:|\n");

        resources.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> md.append("| ").append(e.getKey()).append(" | ").append(String.format("%,d", e.getValue()).replace(',', '_')).append(" |\n"));

        md.append("\n");

        //коды ответа
        md.append("## Коды ответа\n\n");
        md.append("| Код | Название | Количество |\n");
        md.append("|:---:|:---------|-----------:|\n");

        Map<Integer, String> httpStatusNames = getHttpStatusNames();
        periodicityResponseCodes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    String statusName = httpStatusNames.getOrDefault(e.getKey(), "Unknown");
                    md.append("| ").append(e.getKey()).append(" | ").append(statusName).append(" | ")
                            .append(String.format("%,d", e.getValue()).replace(',', '_')).append(" |\n");
                });

        md.append("\n");

        //распределение по датам
        md.append("## Распределение запросов по датам\n\n");
        md.append("| Дата | День недели | Количество | Процент |\n");
        md.append("|:----:|:-----------:|-----------:|--------:|\n");

        requestsPerDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    LocalDate date = e.getKey();
                    int count = e.getValue();
                    double percentage = (count * 100.0) / allRequests;

                    md.append("| ").append(date).append(" | ")
                            .append(getRussianWeekday(date.getDayOfWeek())).append(" | ")
                            .append(String.format("%,d", count).replace(',', '_')).append(" | ")
                            .append(String.format("%.2f%%", percentage)).append(" |\n");
                });

        Files.writeString(Paths.get(outputPath), md.toString());
        System.out.println("Результат сохранен в Markdown: " + outputPath);
    }

    private String getRussianWeekday(java.time.DayOfWeek day) {
        switch (day) {
            case MONDAY: return "Понедельник";
            case TUESDAY: return "Вторник";
            case WEDNESDAY: return "Среда";
            case THURSDAY: return "Четверг";
            case FRIDAY: return "Пятница";
            case SATURDAY: return "Суббота";
            case SUNDAY: return "Воскресенье";
            default: return day.toString();
        }
    }

    private Map<Integer, String> getHttpStatusNames() {
        Map<Integer, String> names = new HashMap<>();
        names.put(200, "OK");
        names.put(201, "Created");
        names.put(204, "No Content");
        names.put(301, "Moved Permanently");
        names.put(302, "Found");
        names.put(304, "Not Modified");
        names.put(400, "Bad Request");
        names.put(401, "Unauthorized");
        names.put(403, "Forbidden");
        names.put(404, "Not Found");
        names.put(405, "Method Not Allowed");
        names.put(408, "Request Timeout");
        names.put(429, "Too Many Requests");
        names.put(500, "Internal Server Error");
        names.put(501, "Not Implemented");
        names.put(502, "Bad Gateway");
        names.put(503, "Service Unavailable");
        names.put(504, "Gateway Timeout");
        return names;
    }
}
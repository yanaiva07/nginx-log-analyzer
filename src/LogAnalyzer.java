
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class LogAnalyzer {
    private static final int success = 0;
    private static final int mistake = 1;
    private static final int invalidArgs = 2;

    private static String path = null;
    private static String format = null;
    private static String output = null;
    static LocalDate fromDate = null;
    static LocalDate toDate = null;

    private static List<Path> filesToProcess = new ArrayList<>();   //список файлов для обработки

    public static void main(String[] args) {
        System.out.println("Анализатор логов");
        try {
            if (args.length == 0) {
                printUsage();         // вызываем метод для вывода инструкции по использованию
                System.exit(invalidArgs); //это метод который завершает работу виртуальной
                //машины (JVM) и останавливает выполнение программы
            }
            //заполняем переменные
            parseArguments(args);     //вызываем метод для разбора аргументов, передаем ему массив args

            //проверка параметров (path, format, output)
            checkCorrectParameter(); // вызываем метод проверки обязательных параметров

            //проверяем форматы файлов (поддерживаемые форматы, существование файла есть они вообещ или нет)
            correctFileFormat();    //вызываем метод проверки форматов файлов

            //проверяем даты (если они есть) на корректность
            correctDate();       //вызываем метод проверки дат

            filesToProcess = expandPath(path);
            if (filesToProcess.isEmpty()) {
                throw new IllegalArgumentException("Не найдено файлов по пути: " + path);
            }


            //проверяем выходной файл (расширение, не существует, можно ли запись)
            correctOutputFile();     //вызываем метод проверки выходного файла

            //если все проверки пройдены то выводим информацию о параметрах
            System.out.println("Все параметры корректны");
            System.out.println("Path: " + path);            //выводим путь к лог-файлу
            System.out.println("Format: " + format);        //выводим формат вывода
            System.out.println("Output: " + output);        //выводим путь для сохранения


            System.out.println("From: " + (fromDate != null ? fromDate : "не указан"));
            System.out.println("To: " + (toDate != null ? toDate : "не указан"));

            System.out.println("Анализируем файлы");
            Statistics statistics = Statistics.readFiles(filesToProcess, fromDate, toDate);

            System.out.println("Всего запросов: " + statistics.allRequests);
            System.out.println("Средний размер ответа: " + String.format("%.2f", statistics.getAverage()) + " байт");
            System.out.println("Максимальный размер ответа: " + statistics.maxSize + " байт");
            System.out.println("Коды ответов: " + statistics.periodicityResponseCodes);
            System.out.println("Топ ресурсов:");

            statistics.resources.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(entry -> System.out.println("  " + entry.getKey() + ": " + entry.getValue()));

            System.out.println("Распределение по датам:");
            statistics.requestsPerDate.forEach((date, count) -> {
                double percentage = (count * 100.0) / statistics.allRequests;
                System.out.printf("  %s: %d (%.2f%%)\n", date, count, percentage);
            });

            if (format.equals("json")) {
                statistics.saveToJson(output, filesToProcess);
            } else if (format.equals("markdown")) {
                statistics.saveToMarkdown(output, filesToProcess, fromDate, toDate);
            }

            System.exit(success);

        } catch (IllegalArgumentException e) {
            //ловим исключения наших проверок
            //System.err - поток для вывода ошибок (красный цвет в консоли)
            System.err.println("Ошибка: " + e.getMessage()); //выводим сообщение об ошибке
            System.exit(invalidArgs); //неправильное использование
        } catch (Exception e) {
            // все непредвиденные исключения
            System.err.println("Непредвиденная ошибка: " + e.getMessage()); //выводим ошибку
            System.exit(mistake); //непредвиденная ошибка
        }

    }

    //метод parseArguments, который принимает массив строк args (аргументы командной строки)
    private static void parseArguments(String[] args) {
        for (int i = 0; i < args.length; ++i) {
            switch (args[i]){
                case "--path" :
                case "--p" :
                    if (i + 1 < args.length) {
                        if (path == null) {
                            path = args[++i];
                        } else {

                            path = path + ";" + args[++i];
                        }
                    }else {
                        throw new IllegalArgumentException("После --path нужно указать значение");
                    }
                    break;

                case "--format":
                case "-f":
                    if (i + 1 < args.length) {
                        //toLowerCase() - преобразуем строку в нижний регистр (чтобы "JSON" и "json" были одинаковыми)
                        format = args[++i].toLowerCase();
                    } else {
                        throw new IllegalArgumentException("После --format нужно указать значение");
                    }
                    break;

                case "--output":
                case "-o":
                    if (i + 1 < args.length) {
                        output = args[++i];
                    } else {
                        throw new IllegalArgumentException("После --output нужно указать значение");
                    }
                    break;

                case "--from":
                    if (i + 1 < args.length) {
                        //parseDate метод для преобразования строки в дату
                        fromDate = parseDate(args[++i]);
                    } else {
                        throw new IllegalArgumentException("После --from нужно указать дату");
                    }
                    break;

                case "--to":
                    if (i + 1 < args.length) {
                        toDate = parseDate(args[++i]);
                    } else {
                        throw new IllegalArgumentException("После --to нужно указать дату");
                    }
                    break;

                //если аргумент не совпал ни с одним из case
                default:
                    throw new IllegalArgumentException("Неизвестный параметр (введена какая-то шляпа): " + args[i]);
            }
        }
    }

    private static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e){
            throw new IllegalArgumentException("Неверный формат даты: " + date +
                    ". Надо использовать ISO8601 (например, 2025-03-01)");
        }
    }

    private static void checkCorrectParameter() {
        //проверка что path был указан)
        if (path == null) { // если path == null
            throw new IllegalArgumentException("Параметр --path обязателен"); // выбрасываем исключение
        }
        if (format == null) {
            throw new IllegalArgumentException("Параметр --format обязателен");
        }

        if (output == null) {
            throw new IllegalArgumentException("Параметр --output обязателен");
        }
    }



    private static void correctFileFormat() {
        //проверка формата вывода
        if (!format.equals("json") && !format.equals("markdown")) {
            throw new IllegalArgumentException("Неподдерживаемый формат: " + format +
                    ". Поддерживаются: json, markdown");
        }

        //если путь содержит разделитель или шаблон, пропускаем проверку существования
        if (path.contains(";") || path.contains("*") || path.contains("?")) {
            return; //не проверяем отдельные файлы, т.к. это шаблон
        }

        //проверка одного файла
        if (!path.endsWith(".log") && !path.endsWith(".txt")) {
            throw new IllegalArgumentException("Файл должен быть .log или .txt: " + path);
        }

        Path filePath = Paths.get(path);
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("Файл не найден: " + path);
        }
        if (!Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("Указанный путь не является файлом: " + path);
        }
        if (!Files.isReadable(filePath)) {
            throw new IllegalArgumentException("Нет прав на чтение файла: " + path);
        }
    }




    private static void correctDate() {
        //проверяем что указаны обе даты
        if (fromDate != null && toDate != null) {
            // fromDate.isAfter(toDate) - проверяет, что fromDate позже toDate
            // fromDate.isEqual(toDate) - проверяет, что даты равны
            if (fromDate.isAfter(toDate) || fromDate.isEqual(toDate)) {
                throw new IllegalArgumentException("from должно быть меньше to");
            }
        }

    }


    private static void correctOutputFile() {
        //создаем объект Path для выходного файла
        Path outputPath = Paths.get(output);

        //определяем правильное расширение в зависимости от формата

        String correctExtension = format.equals("json") ? ".json" : ".md";

        //проверяем расширение файла
        if (!output.endsWith(correctExtension)) {
            throw new IllegalArgumentException("Для формата " + format +
                    " расширение должно быть " + correctExtension);
        }

        //проверяем что файл не существует
        if (Files.exists(outputPath)) {
            throw new IllegalArgumentException("Файл уже существует: " + output);
        }

        //проверяем можно ли создать файл в этой папке
        //outputPath.getParent() - получаем путь к родительской директории
        //может быть null, если путь без папок
        Path parentDir = outputPath.getParent();
        if (parentDir != null && !Files.isWritable(parentDir)) {
            throw new IllegalArgumentException("Нет прав на запись в директорию: " + parentDir);
        }
    }



    private static void printUsage() {
        System.out.println("Как использовать: java LogAnalyzer --path <путь> --format <формат> --output <файл> [--from <дата>] [--to <дата>]");
        System.out.println("Параметры:");
        System.out.println("  --path, -p    : путь к одному или нескольким NGINX лог-файлам (.log или .txt)");
        System.out.println("  --format, -f  : формат вывода результатов (json или markdown)");
        System.out.println("  --output, -o  : путь до файла, куда должен быть сохранён результат работы программы");
        System.out.println("  --from        : начальная дата (ISO8601, например 2025-03-01)");
        System.out.println("  --to          : конечная дата (ISO8601)");
        System.out.println("Пример: java LogAnalyzer --path test.log --format json --output report.json --from 2025-03-01");
    }



    private static List<Path> expandPath(String pattern) {
        List<Path> result = new ArrayList<>();

        //удаляем кавычки, если они есть
        pattern = pattern.replace("'", "").replace("\"", "");

        //разделяем множественные пути (если есть)
        String[] patterns = pattern.split(";");

        for (String singlePattern : patterns) {
            try {
                //проверяем есть ли в пути символы шаблона * или ?
                if (singlePattern.contains("*") || singlePattern.contains("?")) {
                    //получаем родительскую директорию
                    Path parent;
                    String fileNamePattern;

                    int lastSep = Math.max(singlePattern.lastIndexOf('/'), singlePattern.lastIndexOf('\\'));
                    if (lastSep >= 0) {
                        String parentPath = singlePattern.substring(0, lastSep);
                        parent = parentPath.isEmpty() ? Paths.get(".") : Paths.get(parentPath);
                        fileNamePattern = singlePattern.substring(lastSep + 1);
                    } else {
                        parent = Paths.get(".");
                        fileNamePattern = singlePattern;
                    }

                    //ищем все файлы по шаблону
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, fileNamePattern)) {
                        for (Path entry : stream) {
                            if (Files.isRegularFile(entry) && !result.contains(entry)) {
                                result.add(entry);
                            }
                        }
                    }
                } else {
                    //обычный путь без шаблона
                    Path filePath = Paths.get(singlePattern);
                    if (Files.exists(filePath) && Files.isRegularFile(filePath) && !result.contains(filePath)) {
                        result.add(filePath);
                    }
                }
            } catch (IOException e) {
                System.err.println("Ошибка при поиске файлов: " + e.getMessage());
            }
        }

        if (result.isEmpty()) {
            throw new IllegalArgumentException("Не найдено файлов по шаблону: " + pattern);
        }

        return result;
    }

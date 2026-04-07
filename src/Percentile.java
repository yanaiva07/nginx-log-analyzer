
public class Percentile {

    //фиксированное число бакетов — это константа, не зависит от числа элементов
    private static final int NUM_BUCKETS = 10000;

    //максимальное значение размера ответа, которое ожидаем (10 МБ)
    //если встретится значение больше — оно попадёт в последний бакет
    private static final long MAX_VALUE = 10_000_000L;

    //массив счётчиков для каждого бакета — фиксированный размер
    private final long[] buckets = new long[NUM_BUCKETS];

    //общее количество добавленных значений
    private long totalCount = 0;

    //ширина одного бакета
    private final double bucketWidth;

    public Percentile() {
        this.bucketWidth = (double) MAX_VALUE / NUM_BUCKETS;
    }


    public void add(long value) {
        int bucketIndex = getBucketIndex(value);
        buckets[bucketIndex]++;
        totalCount++;
    }


    public double getPercentile(double percentile) {
        if (totalCount == 0) {
            return 0.0;
        }

        //cколько элементов должно быть "слева" от искомого перцентиля
        long targetCount = (long) Math.ceil(percentile * totalCount);

        long cumulativeCount = 0;
        for (int i = 0; i < NUM_BUCKETS; i++) {
            cumulativeCount += buckets[i];
            if (cumulativeCount >= targetCount) {
                //возвращаем середину бакета как приближённое значение
                return (i + 0.5) * bucketWidth;
            }
        }

        //если дошли сюда — возвращаем максимум
        return MAX_VALUE;
    }


    private int getBucketIndex(long value) {
        if (value < 0) value = 0;
        if (value >= MAX_VALUE) return NUM_BUCKETS - 1;
        return (int) (value / bucketWidth);
    }

    public long getTotalCount() {
        return totalCount;
    }
}

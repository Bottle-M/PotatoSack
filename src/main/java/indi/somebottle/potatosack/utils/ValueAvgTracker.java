package indi.somebottle.potatosack.utils;


public class ValueAvgTracker {
    private double sum; // 所有取值之和
    private long count; // 取值次数

    /**
     * 此类用于统计某个变量多次取值中的平均值
     */
    public ValueAvgTracker() {
        sum = 0D;
        count = 0;
    }

    /**
     * 更新一次取值
     * @param value 取值
     */
    public void update(long value) {
        sum += (double) value;
        count++;
    }

    /**
     * 获得目前为止所有取值的平均数
     * @return 目前为止所有取值的平均数（long)
     */
    public long getAvg() {
        if (count == 0) {
            // 防止除 0
            return 0L;
        }
        return Math.round(sum / count);
    }
}

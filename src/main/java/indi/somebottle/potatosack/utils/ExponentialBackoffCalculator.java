package indi.somebottle.potatosack.utils;

/**
 * 指数退避时间计算器
 */
public class ExponentialBackoffCalculator {
    private final long upperBound;
    private final long baseBackoff;
    private long nextBackoffTime;

    /**
     * 初始化指数退避时间计算器
     *
     * @param baseBackoff 基础退避时间（某个单位）
     * @param upperBound  最大退避时间（某个单位）
     */
    public ExponentialBackoffCalculator(long baseBackoff, long upperBound) {
        this.baseBackoff = baseBackoff;
        this.upperBound = upperBound;
        reset();
    }

    /**
     * 初始化指数退避时间计算器，默认退避基础时间 1 个单位，最大退避时间为 Long.MAX_VALUE 个单位
     */
    public ExponentialBackoffCalculator() {
        this(1, Long.MAX_VALUE);
    }

    /**
     * 退避一次，更新下次退避时间
     */
    public void backoff() {
        nextBackoffTime = Math.min(nextBackoffTime * 2, upperBound);
    }

    /**
     * 获取下次退避时间，此方法不会更新退避时间
     *
     * @return 下次退避时间（某个单位）
     */
    public long getNextBackoffTime() {
        return nextBackoffTime;
    }

    public void reset() {
        nextBackoffTime = Math.min(baseBackoff, upperBound);
    }
}

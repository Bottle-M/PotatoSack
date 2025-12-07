package indi.somebottle.potatosack.utils;

import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 仅对写入字节数量进行计数的输出流
 */
public class CounterOutputStream extends OutputStream {
    // 计数器
    private final AtomicLong counter;

    /**
     * 用外部计数器初始化 CounterOutputStream
     *
     * @param counter 外部提供的计数器
     */
    public CounterOutputStream(AtomicLong counter) {
        this.counter = counter;
    }

    /**
     * 默认构造函数，初始化内部计数器
     */
    public CounterOutputStream() {
        this.counter = new AtomicLong(0);
    }

    @Override
    public void write(int b) {
        counter.incrementAndGet();
    }

    /**
     * 获取当前计数值
     *
     * @return 当前计数值
     */
    public long getCount() {
        return counter.get();
    }

}
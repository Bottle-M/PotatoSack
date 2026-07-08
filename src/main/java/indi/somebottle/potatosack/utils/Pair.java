package indi.somebottle.potatosack.utils;

/**
 * 存储一对数据
 */
public class Pair<V1, V2> {
    private final V1 val1;
    private final V2 val2;

    public Pair(V1 val1, V2 val2) {
        this.val1 = val1;
        this.val2 = val2;
    }

    public V1 getFirst() {
        return val1;
    }

    public V2 getSecond() {
        return val2;
    }

    public static <V1, V2> Pair<V1, V2> of(V1 val1, V2 val2) {
        return new Pair<>(val1, val2);
    }
}

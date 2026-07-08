package indi.somebottle.potatosack.clients.dropbox.entities;

import com.google.gson.annotations.SerializedName;

/**
 * Dropbox API 错误响应实体类
 * <p>
 * 表示 Dropbox API 返回的错误响应结构。
 * 包含错误摘要和详细错误信息，支持嵌套的错误结构。
 * </p>
 * <p>
 * 主要用于解析上传会话偏移量不匹配错误（409），
 * 从中提取服务器记录的正确偏移量。
 * </p>
 *
 * @see indi.somebottle.potatosack.clients.dropbox.utils.DropboxErrorUtils
 */
public class DropboxApiError {
    /**
     * 错误摘要
     */
    @SerializedName("error_summary")
    private String errorSummary;

    /**
     * 错误详细信息
     */
    private ErrorDetails error;

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary;
    }

    public ErrorDetails getError() {
        return error;
    }

    public void setError(ErrorDetails error) {
        this.error = error;
    }

    /**
     * 获取错误类型标签
     *
     * @return 错误类型标签，若无则返回空字符串
     */
    public String getTag() {
        if (error == null) {
            return "";
        }
        return error.getTag();
    }

    /**
     * 获取正确的上传偏移量
     * <p>
     * 从错误信息中提取正确的偏移量。
     * 先尝试从 {@code error.correctOffset} 获取，
     * 若为空则从 {@code error.lookupFailed.correctOffset} 获取。
     * </p>
     *
     * @return 正确的偏移量，若无法获取则返回 {@code null}
     */
    public Long getCorrectOffset() {
        if (error == null) {
            return null;
        }
        Long directOffset = error.getCorrectOffset();
        if (directOffset != null) {
            return directOffset;
        }
        if (error.getLookupFailed() != null) {
            return error.getLookupFailed().getCorrectOffset();
        }
        return null;
    }

    /**
     * 错误详细信息
     */
    public static class ErrorDetails {
        @SerializedName(".tag")
        private String tag;

        @SerializedName("correct_offset")
        private Long correctOffset;

        @SerializedName("lookup_failed")
        private LookupFailed lookupFailed;

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }

        public Long getCorrectOffset() {
            return correctOffset;
        }

        public void setCorrectOffset(Long correctOffset) {
            this.correctOffset = correctOffset;
        }

        public LookupFailed getLookupFailed() {
            return lookupFailed;
        }

        public void setLookupFailed(LookupFailed lookupFailed) {
            this.lookupFailed = lookupFailed;
        }
    }

    /**
     * 查找失败错误详情
     */
    public static class LookupFailed {
        @SerializedName(".tag")
        private String tag;

        @SerializedName("correct_offset")
        private Long correctOffset;

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }

        public Long getCorrectOffset() {
            return correctOffset;
        }

        public void setCorrectOffset(Long correctOffset) {
            this.correctOffset = correctOffset;
        }
    }
}

package indi.somebottle.potatosack.entities.onedrive;

import java.util.List;

/**
 * PUT请求uploadUrl的响应
 */
public class PutSessionResp {
    private String expirationDateTime;
    private List<String> nextExpectedRanges;

    public String getExpirationDateTime() {
        return expirationDateTime;
    }

    public void setExpirationDateTime(String expirationDateTime) {
        this.expirationDateTime = expirationDateTime;
    }

    public List<String> getNextExpectedRanges() {
        return nextExpectedRanges;
    }

    public void setNextExpectedRanges(List<String> nextExpectedRanges) {
        this.nextExpectedRanges = nextExpectedRanges;
    }
}

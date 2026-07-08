package indi.somebottle.potatosack.clients.onedrive.entities;

import java.util.List;

/**
 * PUT/GET 请求 uploadUrl 的响应
 */
public class OneDrivePutOrGetSessionResp {
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

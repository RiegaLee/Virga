package io.github.kloping.qqbot.http.data;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;

/** 修改 QQ 指令面板关联群的请求体。 */
@Data
public class PanelTargetRequest {
    private String op;

    @SerializedName("group_openids")
    private List<String> groupOpenIds;

    public PanelTargetRequest() {
    }

    public PanelTargetRequest(String op, List<String> groupOpenIds) {
        this.op = op;
        this.groupOpenIds = groupOpenIds;
    }
}

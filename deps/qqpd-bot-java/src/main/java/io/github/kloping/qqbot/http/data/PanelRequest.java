package io.github.kloping.qqbot.http.data;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;

/** 创建 QQ 指令面板的请求体。 */
@Data
public class PanelRequest {
    private String scope;

    @SerializedName("target_type")
    private String targetType;

    @SerializedName("group_openids")
    private List<String> groupOpenIds;

    private PanelDefinition panel;

    public PanelRequest() {
    }

    public PanelRequest(String scope, String targetType, List<String> groupOpenIds, PanelDefinition panel) {
        this.scope = scope;
        this.targetType = targetType;
        this.groupOpenIds = groupOpenIds;
        this.panel = panel;
    }
}

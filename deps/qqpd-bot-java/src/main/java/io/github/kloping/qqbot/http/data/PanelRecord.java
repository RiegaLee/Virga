package io.github.kloping.qqbot.http.data;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;

/** 已创建的 QQ 指令面板记录。 */
@Data
public class PanelRecord {
    @SerializedName("panel_id")
    private String panelId;

    private String scope;

    @SerializedName("target_type")
    private String targetType;

    private PanelDefinition panel;

    @SerializedName("group_openids")
    private List<String> groupOpenIds;
}

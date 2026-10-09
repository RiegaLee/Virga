package io.github.kloping.qqbot.http.data;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/** QQ 指令面板中的单个项目。 */
@Data
public class PanelItem {
    private String type = "command";
    private String name;
    private String desc;

    @SerializedName("only_admin")
    private boolean onlyAdmin;

    public PanelItem() {
    }

    public PanelItem(String name, String desc, boolean onlyAdmin) {
        this.name = name;
        this.desc = desc;
        this.onlyAdmin = onlyAdmin;
    }
}

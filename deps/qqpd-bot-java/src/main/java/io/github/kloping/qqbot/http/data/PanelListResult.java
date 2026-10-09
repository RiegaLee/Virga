package io.github.kloping.qqbot.http.data;

import lombok.Data;

import java.util.Collections;
import java.util.List;

/** 查询 QQ 指令面板列表的响应。 */
@Data
public class PanelListResult {
    private List<PanelRecord> records = Collections.emptyList();
}

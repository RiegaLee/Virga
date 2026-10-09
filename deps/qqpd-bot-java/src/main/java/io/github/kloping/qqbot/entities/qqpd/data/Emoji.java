package io.github.kloping.qqbot.entities.qqpd.data;

import com.alibaba.fastjson.annotation.JSONField;
import io.github.kloping.qqbot.api.SendAble;
import io.github.kloping.qqbot.api.SenderAndCidMidGetter;
import io.github.kloping.qqbot.http.data.Result;

import java.util.LinkedList;
import java.util.List;

/**
 * <table><thead><tr><th>字段名</th> <th>类型</th> <th>描述</th></tr></thead> <tbody><tr><td>id</td> <td>string</td> <td>表情ID，系统表情使用数字为ID，emoji使用emoji本身为id，参考 Emoji 列表</td></tr> <tr><td>type</td> <td>uint32</td> <td>表情类型 EmojiType</td></tr></tbody></table>
 *
 * @author github.kloping
 */
public class Emoji implements SendAble {
    public static final List<Emoji> VALUES = new LinkedList<>();
    public static final Emoji 惊讶 = new Emoji(1, 0, "惊讶");
    public static final Emoji 撇嘴 = new Emoji(1, 1, "撇嘴");
    public static final Emoji 色 = new Emoji(1, 2, "色");
    public static final Emoji 发呆 = new Emoji(1, 3, "发呆");
    public static final Emoji 得意 = new Emoji(1, 4, "得意");
    public static final Emoji 流泪 = new Emoji(1, 5, "流泪");
    public static final Emoji 害羞 = new Emoji(1, 6, "害羞");
    public static final Emoji 闭嘴 = new Emoji(1, 7, "闭嘴");
    public static final Emoji 睡 = new Emoji(1, 8, "睡");
    public static final Emoji 大哭 = new Emoji(1, 9, "大哭");
    public static final Emoji 尴尬 = new Emoji(1, 10, "尴尬");
    public static final Emoji 发怒 = new Emoji(1, 11, "发怒");
    public static final Emoji 调皮 = new Emoji(1, 12, "调皮");
    public static final Emoji 呲牙 = new Emoji(1, 13, "呲牙");
    public static final Emoji 微笑 = new Emoji(1, 14, "微笑");
    public static final Emoji 难过 = new Emoji(1, 15, "难过");
    public static final Emoji 酷 = new Emoji(1, 16, "酷");
    public static final Emoji 抓狂 = new Emoji(1, 18, "抓狂");
    public static final Emoji 吐 = new Emoji(1, 19, "吐");
    public static final Emoji 偷笑 = new Emoji(1, 20, "偷笑");
    public static final Emoji 可爱 = new Emoji(1, 21, "可爱");
    public static final Emoji 白眼 = new Emoji(1, 22, "白眼");
    public static final Emoji 傲慢 = new Emoji(1, 23, "傲慢");
    public static final Emoji 饥饿 = new Emoji(1, 24, "饥饿");
    public static final Emoji 困 = new Emoji(1, 25, "困");
    public static final Emoji 惊恐 = new Emoji(1, 26, "惊恐");
    public static final Emoji 流汗 = new Emoji(1, 27, "流汗");
    public static final Emoji 憨笑 = new Emoji(1, 28, "憨笑");
    public static final Emoji 悠闲 = new Emoji(1, 29, "悠闲");
    public static final Emoji 奋斗 = new Emoji(1, 30, "奋斗");
    public static final Emoji 咒骂 = new Emoji(1, 31, "咒骂");
    public static final Emoji 疑问 = new Emoji(1, 32, "疑问");
    public static final Emoji 嘘 = new Emoji(1, 33, "嘘");
    public static final Emoji 晕 = new Emoji(1, 34, "晕");
    public static final Emoji 折磨 = new Emoji(1, 35, "折磨");
    public static final Emoji 衰 = new Emoji(1, 36, "衰");
    public static final Emoji 骷髅 = new Emoji(1, 37, "骷髅");
    public static final Emoji 敲打 = new Emoji(1, 38, "敲打");
    public static final Emoji 再见 = new Emoji(1, 39, "再见");
    public static final Emoji 发抖 = new Emoji(1, 41, "发抖");
    public static final Emoji 爱情 = new Emoji(1, 42, "爱情");
    public static final Emoji 跳跳 = new Emoji(1, 43, "跳跳");
    public static final Emoji 猪头 = new Emoji(1, 46, "猪头");
    public static final Emoji 拥抱 = new Emoji(1, 49, "拥抱");
    public static final Emoji 蛋糕 = new Emoji(1, 53, "蛋糕");
    public static final Emoji 闪电 = new Emoji(1, 54, "闪电");
    public static final Emoji 炸弹 = new Emoji(1, 55, "炸弹");
    public static final Emoji 刀 = new Emoji(1, 56, "刀");
    public static final Emoji 足球 = new Emoji(1, 57, "足球");
    public static final Emoji 便便 = new Emoji(1, 59, "便便");
    public static final Emoji 咖啡 = new Emoji(1, 60, "咖啡");
    public static final Emoji 饭 = new Emoji(1, 61, "饭");
    public static final Emoji 玫瑰 = new Emoji(1, 63, "玫瑰");
    public static final Emoji 凋谢 = new Emoji(1, 64, "凋谢");
    public static final Emoji 爱心 = new Emoji(1, 66, "爱心");
    public static final Emoji 心碎 = new Emoji(1, 67, "心碎");
    public static final Emoji 礼物 = new Emoji(1, 69, "礼物");
    public static final Emoji 太阳 = new Emoji(1, 74, "太阳");
    public static final Emoji 月亮 = new Emoji(1, 75, "月亮");
    public static final Emoji 赞 = new Emoji(1, 76, "赞");
    public static final Emoji 踩 = new Emoji(1, 77, "踩");
    public static final Emoji 握手 = new Emoji(1, 78, "握手");
    public static final Emoji 胜利 = new Emoji(1, 79, "胜利");
    public static final Emoji 飞吻 = new Emoji(1, 85, "飞吻");
    public static final Emoji 怄火 = new Emoji(1, 86, "怄火");
    public static final Emoji 西瓜 = new Emoji(1, 89, "西瓜");
    public static final Emoji 冷汗 = new Emoji(1, 96, "冷汗");
    public static final Emoji 擦汗 = new Emoji(1, 97, "擦汗");
    public static final Emoji 抠鼻 = new Emoji(1, 98, "抠鼻");
    public static final Emoji 鼓掌 = new Emoji(1, 99, "鼓掌");
    public static final Emoji 糗大了 = new Emoji(1, 100, "糗大了");
    public static final Emoji 坏笑 = new Emoji(1, 101, "坏笑");
    public static final Emoji 左哼哼 = new Emoji(1, 102, "左哼哼");
    public static final Emoji 右哼哼 = new Emoji(1, 103, "右哼哼");
    public static final Emoji 哈欠 = new Emoji(1, 104, "哈欠");
    public static final Emoji 鄙视 = new Emoji(1, 105, "鄙视");
    public static final Emoji 委屈 = new Emoji(1, 106, "委屈");
    public static final Emoji 快哭了 = new Emoji(1, 107, "快哭了");
    public static final Emoji 阴险 = new Emoji(1, 108, "阴险");
    public static final Emoji 左亲亲 = new Emoji(1, 109, "左亲亲");
    public static final Emoji 吓 = new Emoji(1, 110, "吓");
    public static final Emoji 可怜 = new Emoji(1, 111, "可怜");
    public static final Emoji 菜刀 = new Emoji(1, 112, "菜刀");
    public static final Emoji 啤酒 = new Emoji(1, 113, "啤酒");
    public static final Emoji 篮球 = new Emoji(1, 114, "篮球");
    public static final Emoji 乒乓 = new Emoji(1, 115, "乒乓");
    public static final Emoji 示爱 = new Emoji(1, 116, "示爱");
    public static final Emoji 瓢虫 = new Emoji(1, 117, "瓢虫");
    public static final Emoji 抱拳 = new Emoji(1, 118, "抱拳");
    public static final Emoji 勾引 = new Emoji(1, 119, "勾引");
    public static final Emoji 拳头 = new Emoji(1, 120, "拳头");
    public static final Emoji 差劲 = new Emoji(1, 121, "差劲");
    public static final Emoji 爱你 = new Emoji(1, 122, "爱你");
    public static final Emoji NO = new Emoji(1, 123, "NO");
    public static final Emoji OK = new Emoji(1, 124, "OK");
    public static final Emoji 转圈 = new Emoji(1, 125, "转圈");
    public static final Emoji 磕头 = new Emoji(1, 126, "磕头");
    public static final Emoji 回头 = new Emoji(1, 127, "回头");
    public static final Emoji 跳绳 = new Emoji(1, 128, "跳绳");
    public static final Emoji 挥手 = new Emoji(1, 129, "挥手");
    public static final Emoji 激动 = new Emoji(1, 130, "激动");
    public static final Emoji 街舞 = new Emoji(1, 131, "街舞");
    public static final Emoji 献吻 = new Emoji(1, 132, "献吻");
    public static final Emoji 左太极 = new Emoji(1, 133, "左太极");
    public static final Emoji 右太极 = new Emoji(1, 134, "右太极");
    public static final Emoji 双喜 = new Emoji(1, 136, "双喜");
    public static final Emoji 鞭炮 = new Emoji(1, 137, "鞭炮");
    public static final Emoji 灯笼 = new Emoji(1, 138, "灯笼");
    public static final Emoji K歌 = new Emoji(1, 140, "K歌");
    public static final Emoji 喝彩 = new Emoji(1, 144, "喝彩");
    public static final Emoji 祈祷 = new Emoji(1, 145, "祈祷");
    public static final Emoji 爆筋 = new Emoji(1, 146, "爆筋");
    public static final Emoji 棒棒糖 = new Emoji(1, 147, "棒棒糖");
    public static final Emoji 喝奶 = new Emoji(1, 148, "喝奶");
    public static final Emoji 飞机 = new Emoji(1, 151, "飞机");
    public static final Emoji 钞票 = new Emoji(1, 158, "钞票");
    public static final Emoji 药 = new Emoji(1, 168, "药");
    public static final Emoji 手枪 = new Emoji(1, 169, "手枪");
    public static final Emoji 茶 = new Emoji(1, 171, "茶");
    public static final Emoji 眨眼睛 = new Emoji(1, 172, "眨眼睛");
    public static final Emoji 泪奔 = new Emoji(1, 173, "泪奔");
    public static final Emoji 无奈 = new Emoji(1, 174, "无奈");
    public static final Emoji 卖萌 = new Emoji(1, 175, "卖萌");
    public static final Emoji 小纠结 = new Emoji(1, 176, "小纠结");
    public static final Emoji 喷血 = new Emoji(1, 177, "喷血");
    public static final Emoji 斜眼笑 = new Emoji(1, 178, "斜眼笑");
    public static final Emoji doge = new Emoji(1, 179, "doge");
    public static final Emoji 惊喜 = new Emoji(1, 180, "惊喜");
    public static final Emoji 骚扰 = new Emoji(1, 181, "骚扰");
    public static final Emoji 笑哭 = new Emoji(1, 182, "笑哭");
    public static final Emoji 我最美 = new Emoji(1, 183, "我最美");
    public static final Emoji 河蟹 = new Emoji(1, 184, "河蟹");
    public static final Emoji 羊驼 = new Emoji(1, 185, "羊驼");
    public static final Emoji 幽灵 = new Emoji(1, 187, "幽灵");
    public static final Emoji 蛋 = new Emoji(1, 188, "蛋");
    public static final Emoji 菊花 = new Emoji(1, 190, "菊花");
    public static final Emoji 红包 = new Emoji(1, 192, "红包");
    public static final Emoji 大笑 = new Emoji(1, 193, "大笑");
    public static final Emoji 不开心 = new Emoji(1, 194, "不开心");
    public static final Emoji 冷漠 = new Emoji(1, 197, "冷漠");
    public static final Emoji 呃 = new Emoji(1, 198, "呃");
    public static final Emoji 好棒 = new Emoji(1, 199, "好棒");
    public static final Emoji 拜托 = new Emoji(1, 200, "拜托");
    public static final Emoji 点赞 = new Emoji(1, 201, "点赞");
    public static final Emoji 无聊 = new Emoji(1, 202, "无聊");
    public static final Emoji 托脸 = new Emoji(1, 203, "托脸");
    public static final Emoji 吃 = new Emoji(1, 204, "吃");
    public static final Emoji 送花 = new Emoji(1, 205, "送花");
    public static final Emoji 害怕 = new Emoji(1, 206, "害怕");
    public static final Emoji 花痴 = new Emoji(1, 207, "花痴");
    public static final Emoji 小样儿 = new Emoji(1, 208, "小样儿");
    public static final Emoji 飙泪 = new Emoji(1, 210, "飙泪");
    public static final Emoji 我不看 = new Emoji(1, 211, "我不看");
    public static final Emoji 托腮 = new Emoji(1, 212, "托腮");
    public static final Emoji 啵啵 = new Emoji(1, 214, "啵啵");
    public static final Emoji 糊脸 = new Emoji(1, 215, "糊脸");
    public static final Emoji 拍头 = new Emoji(1, 216, "拍头");
    public static final Emoji 扯一扯 = new Emoji(1, 217, "扯一扯");
    public static final Emoji 舔一舔 = new Emoji(1, 218, "舔一舔");
    public static final Emoji 蹭一蹭 = new Emoji(1, 219, "蹭一蹭");
    public static final Emoji 拽炸天 = new Emoji(1, 220, "拽炸天");
    public static final Emoji 顶呱呱 = new Emoji(1, 221, "顶呱呱");
    public static final Emoji 抱抱 = new Emoji(1, 222, "抱抱");
    public static final Emoji 暴击 = new Emoji(1, 223, "暴击");
    public static final Emoji 开枪 = new Emoji(1, 224, "开枪");
    public static final Emoji 撩一撩 = new Emoji(1, 225, "撩一撩");
    public static final Emoji 拍桌 = new Emoji(1, 226, "拍桌");
    public static final Emoji 拍手 = new Emoji(1, 227, "拍手");
    public static final Emoji 恭喜 = new Emoji(1, 228, "恭喜");
    public static final Emoji 干杯 = new Emoji(1, 229, "干杯");
    public static final Emoji 嘲讽 = new Emoji(1, 230, "嘲讽");
    public static final Emoji 哼 = new Emoji(1, 231, "哼");
    public static final Emoji 佛系 = new Emoji(1, 232, "佛系");
    public static final Emoji 掐一掐 = new Emoji(1, 233, "掐一掐");
    public static final Emoji 惊呆 = new Emoji(1, 234, "惊呆");
    public static final Emoji 颤抖 = new Emoji(1, 235, "颤抖");
    public static final Emoji 啃头 = new Emoji(1, 236, "啃头");
    public static final Emoji 偷看 = new Emoji(1, 237, "偷看");
    public static final Emoji 扇脸 = new Emoji(1, 238, "扇脸");
    public static final Emoji 原谅 = new Emoji(1, 239, "原谅");
    public static final Emoji 喷脸 = new Emoji(1, 240, "喷脸");
    public static final Emoji 生日快乐 = new Emoji(1, 241, "生日快乐");
    public static final Emoji 头撞击 = new Emoji(1, 242, "头撞击");
    public static final Emoji 甩头 = new Emoji(1, 243, "甩头");
    public static final Emoji 扔狗 = new Emoji(1, 244, "扔狗");
    public static final Emoji 加油必胜 = new Emoji(1, 245, "加油必胜");
    public static final Emoji 加油抱抱 = new Emoji(1, 246, "加油抱抱");
    public static final Emoji 口罩护体 = new Emoji(1, 247, "口罩护体");
    public static final Emoji 搬砖中 = new Emoji(1, 260, "搬砖中");
    public static final Emoji 忙到飞起 = new Emoji(1, 261, "忙到飞起");
    public static final Emoji 脑阔疼 = new Emoji(1, 262, "脑阔疼");
    public static final Emoji 沧桑 = new Emoji(1, 263, "沧桑");
    public static final Emoji 捂脸 = new Emoji(1, 264, "捂脸");
    public static final Emoji 辣眼睛 = new Emoji(1, 265, "辣眼睛");
    public static final Emoji 哦哟 = new Emoji(1, 266, "哦哟");
    public static final Emoji 头秃 = new Emoji(1, 267, "头秃");
    public static final Emoji 问号脸 = new Emoji(1, 268, "问号脸");
    public static final Emoji 暗中观察 = new Emoji(1, 269, "暗中观察");
    public static final Emoji emm = new Emoji(1, 270, "emm");
    public static final Emoji 吃瓜 = new Emoji(1, 271, "吃瓜");
    public static final Emoji 呵呵哒 = new Emoji(1, 272, "呵呵哒");
    public static final Emoji 我酸了 = new Emoji(1, 273, "我酸了");
    public static final Emoji 太南了 = new Emoji(1, 274, "太南了");
    public static final Emoji 辣椒酱 = new Emoji(1, 276, "辣椒酱");
    public static final Emoji 汪汪 = new Emoji(1, 277, "汪汪");
    public static final Emoji 汗 = new Emoji(1, 278, "汗");
    public static final Emoji 打脸 = new Emoji(1, 279, "打脸");
    public static final Emoji 击掌 = new Emoji(1, 280, "击掌");
    public static final Emoji 无眼笑 = new Emoji(1, 281, "无眼笑");
    public static final Emoji 敬礼 = new Emoji(1, 282, "敬礼");
    public static final Emoji 狂笑 = new Emoji(1, 283, "狂笑");
    public static final Emoji 面无表情 = new Emoji(1, 284, "面无表情");
    public static final Emoji 摸鱼 = new Emoji(1, 285, "摸鱼");
    public static final Emoji 魔鬼笑 = new Emoji(1, 286, "魔鬼笑");
    public static final Emoji 哦 = new Emoji(1, 287, "哦");
    public static final Emoji 请 = new Emoji(1, 288, "请");
    public static final Emoji 睁眼 = new Emoji(1, 289, "睁眼");
    public static final Emoji 敲开心 = new Emoji(1, 290, "敲开心");
    public static final Emoji 震惊 = new Emoji(1, 291, "震惊");
    public static final Emoji 让我康康 = new Emoji(1, 292, "让我康康");
    public static final Emoji 摸锦鲤 = new Emoji(1, 293, "摸锦鲤");
    public static final Emoji 期待 = new Emoji(1, 294, "期待");
    public static final Emoji 拿到红包 = new Emoji(1, 295, "拿到红包");
    public static final Emoji 真好 = new Emoji(1, 296, "真好");
    public static final Emoji 拜谢 = new Emoji(1, 297, "拜谢");
    public static final Emoji 元宝 = new Emoji(1, 298, "元宝");
    public static final Emoji 牛啊 = new Emoji(1, 299, "牛啊");
    public static final Emoji 胖三斤 = new Emoji(1, 300, "胖三斤");
    public static final Emoji 好闪 = new Emoji(1, 301, "好闪");
    public static final Emoji 左拜年 = new Emoji(1, 302, "左拜年");
    public static final Emoji 右拜年 = new Emoji(1, 303, "右拜年");
    public static final Emoji 红包包 = new Emoji(1, 304, "红包包");
    public static final Emoji 右亲亲 = new Emoji(1, 305, "右亲亲");
    public static final Emoji 牛气冲天 = new Emoji(1, 306, "牛气冲天");
    public static final Emoji 喵喵 = new Emoji(1, 307, "喵喵");
    public static final Emoji 求红包 = new Emoji(1, 308, "求红包");
    public static final Emoji 谢红包 = new Emoji(1, 309, "谢红包");
    public static final Emoji 新年烟花 = new Emoji(1, 310, "新年烟花");
    public static final Emoji 打call = new Emoji(1, 311, "打call");
    public static final Emoji 变形 = new Emoji(1, 312, "变形");
    public static final Emoji 嗑到了 = new Emoji(1, 313, "嗑到了");
    public static final Emoji 仔细分析 = new Emoji(1, 314, "仔细分析");
    public static final Emoji 加油 = new Emoji(1, 315, "加油");
    public static final Emoji 我没事 = new Emoji(1, 316, "我没事");
    public static final Emoji 菜狗 = new Emoji(1, 317, "菜狗");
    public static final Emoji 崇拜 = new Emoji(1, 318, "崇拜");
    public static final Emoji 比心 = new Emoji(1, 319, "比心");
    public static final Emoji 庆祝 = new Emoji(1, 320, "庆祝");
    public static final Emoji 老色痞 = new Emoji(1, 321, "老色痞");
    public static final Emoji 拒绝 = new Emoji(1, 322, "拒绝");
    public static final Emoji 嫌弃 = new Emoji(1, 323, "嫌弃");
    public static final Emoji 吃糖 = new Emoji(1, 324, "吃糖");
    public static final Emoji 惊吓 = new Emoji(1, 325, "惊吓");
    public static final Emoji 生气 = new Emoji(1, 326, "生气");
    public static final Emoji 举牌牌 = new Emoji(1, 332, "举牌牌");
    public static final Emoji 烟花 = new Emoji(1, 333, "烟花");
    public static final Emoji 虎虎生威 = new Emoji(1, 334, "虎虎生威");
    public static final Emoji 豹富 = new Emoji(1, 336, "豹富");
    public static final Emoji 花朵脸 = new Emoji(1, 337, "花朵脸");
    public static final Emoji 我想开了 = new Emoji(1, 338, "我想开了");
    public static final Emoji 舔屏 = new Emoji(1, 339, "舔屏");
    public static final Emoji 打招呼 = new Emoji(1, 341, "打招呼");
    public static final Emoji 酸Q = new Emoji(1, 342, "酸Q");
    public static final Emoji 我方了 = new Emoji(1, 343, "我方了");
    public static final Emoji 大怨种 = new Emoji(1, 344, "大怨种");
    public static final Emoji 红包多多 = new Emoji(1, 345, "红包多多");
    public static final Emoji 你真棒棒 = new Emoji(1, 346, "你真棒棒");
    public static final Emoji 大展宏兔 = new Emoji(1, 347, "大展宏兔");
    public static final Emoji 福萝卜 = new Emoji(1, 348, "福萝卜");
    public static final Emoji 亲亲 = new Emoji(1, 360, "亲亲");
    public static final Emoji 狗狗笑哭 = new Emoji(1, 361, "狗狗笑哭");
    public static final Emoji 好兄弟 = new Emoji(1, 362, "好兄弟");
    public static final Emoji 狗狗可怜 = new Emoji(1, 363, "狗狗可怜");
    public static final Emoji 超级赞 = new Emoji(1, 364, "超级赞");
    public static final Emoji 狗狗生气 = new Emoji(1, 365, "狗狗生气");
    public static final Emoji 芒狗 = new Emoji(1, 366, "芒狗");
    public static final Emoji 狗狗疑问 = new Emoji(1, 367, "狗狗疑问");
    public static final Emoji 奥特笑哭 = new Emoji(1, 368, "奥特笑哭");
    public static final Emoji 彩虹 = new Emoji(1, 369, "彩虹");
    public static final Emoji 祝贺 = new Emoji(1, 370, "祝贺");
    public static final Emoji 冒泡 = new Emoji(1, 371, "冒泡");
    public static final Emoji 气呼呼 = new Emoji(1, 372, "气呼呼");
    public static final Emoji 忙 = new Emoji(1, 373, "忙");
    public static final Emoji 波波流泪 = new Emoji(1, 374, "波波流泪");
    public static final Emoji 超级鼓掌 = new Emoji(1, 375, "超级鼓掌");
    public static final Emoji 跺脚 = new Emoji(1, 376, "跺脚");
    public static final Emoji 嗨 = new Emoji(1, 377, "嗨");
    public static final Emoji 企鹅笑哭 = new Emoji(1, 378, "企鹅笑哭");
    public static final Emoji 企鹅流泪 = new Emoji(1, 379, "企鹅流泪");
    public static final Emoji 真棒 = new Emoji(1, 380, "真棒");
    public static final Emoji 路过 = new Emoji(1, 381, "路过");
    public static final Emoji emo = new Emoji(1, 382, "emo");
    public static final Emoji 企鹅爱心 = new Emoji(1, 383, "企鹅爱心");
    public static final Emoji 晚安 = new Emoji(1, 384, "晚安");
    public static final Emoji 太气了 = new Emoji(1, 385, "太气了");
    public static final Emoji 呜呜呜 = new Emoji(1, 386, "呜呜呜");
    public static final Emoji 太好笑 = new Emoji(1, 387, "太好笑");
    public static final Emoji 太头疼 = new Emoji(1, 388, "太头疼");
    public static final Emoji 太赞了 = new Emoji(1, 389, "太赞了");
    public static final Emoji 太头秃 = new Emoji(1, 390, "太头秃");
    public static final Emoji 太沧桑 = new Emoji(1, 391, "太沧桑");
    public static final Emoji 狼狗 = new Emoji(1, 396, "狼狗");
    public static final Emoji 抛媚眼 = new Emoji(1, 397, "抛媚眼");
    public static final Emoji 超级ok = new Emoji(1, 398, "超级ok");
    public static final Emoji tui = new Emoji(1, 399, "tui");
    public static final Emoji 快乐 = new Emoji(1, 400, "快乐");
    public static final Emoji 超级旋转 = new Emoji(1, 401, "超级旋转");
    public static final Emoji 别说话 = new Emoji(1, 402, "别说话");
    public static final Emoji 出去玩 = new Emoji(1, 403, "出去玩");
    public static final Emoji 闪亮登场 = new Emoji(1, 404, "闪亮登场");
    public static final Emoji 好运来 = new Emoji(1, 405, "好运来");
    public static final Emoji 姐是女王 = new Emoji(1, 406, "姐是女王");
    public static final Emoji 我听听 = new Emoji(1, 407, "我听听");
    public static final Emoji 臭美 = new Emoji(1, 408, "臭美");
    public static final Emoji 送你花花 = new Emoji(1, 409, "送你花花");
    public static final Emoji 么么哒 = new Emoji(1, 410, "么么哒");
    public static final Emoji 一起嗨 = new Emoji(1, 411, "一起嗨");
    public static final Emoji 开心 = new Emoji(1, 412, "开心");
    public static final Emoji 摇起来 = new Emoji(1, 413, "摇起来");
    /**
     * 以上是qq emoji
     * <hr>
     * 以下是原生emoji
     */
    public static final Emoji 晴天 = new Emoji(2, 9728, "晴天");
    public static final Emoji 咖啡2 = new Emoji(2, 9749, "咖啡");
    public static final Emoji 可爱2 = new Emoji(2, 9786, "可爱");
    public static final Emoji 闪光 = new Emoji(2, 10024, "闪光");
    public static final Emoji 错误 = new Emoji(2, 10060, "错误");
    public static final Emoji 问号 = new Emoji(2, 10068, "问号");
    public static final Emoji 玫瑰2 = new Emoji(2, 127801, "玫瑰");
    public static final Emoji 西瓜2 = new Emoji(2, 127817, "西瓜");
    public static final Emoji 苹果 = new Emoji(2, 127822, "苹果");
    public static final Emoji 草莓 = new Emoji(2, 127827, "草莓");
    public static final Emoji 拉面 = new Emoji(2, 127836, "拉面");
    public static final Emoji 面包 = new Emoji(2, 127838, "面包");
    public static final Emoji 刨冰 = new Emoji(2, 127847, "刨冰");
    public static final Emoji 啤酒2 = new Emoji(2, 127866, "啤酒");
    public static final Emoji 干杯2 = new Emoji(2, 127867, "干杯");
    public static final Emoji 庆祝2 = new Emoji(2, 127881, "庆祝");
    public static final Emoji 虫 = new Emoji(2, 128027, "虫");
    public static final Emoji 牛 = new Emoji(2, 128046, "牛");
    public static final Emoji 鲸鱼 = new Emoji(2, 128051, "鲸鱼");
    public static final Emoji 猴 = new Emoji(2, 128053, "猴");
    public static final Emoji 拳头2 = new Emoji(2, 128074, "拳头");
    public static final Emoji 好的 = new Emoji(2, 128076, "好的");
    public static final Emoji 厉害 = new Emoji(2, 128077, "厉害");
    public static final Emoji 鼓掌2 = new Emoji(2, 128079, "鼓掌");
    public static final Emoji 内衣 = new Emoji(2, 128089, "内衣");
    public static final Emoji 男孩 = new Emoji(2, 128102, "男孩");
    public static final Emoji 爸爸 = new Emoji(2, 128104, "爸爸");
    public static final Emoji 爱心2 = new Emoji(2, 128147, "爱心");
    public static final Emoji 礼物2 = new Emoji(2, 128157, "礼物");
    public static final Emoji 睡觉 = new Emoji(2, 128164, "睡觉");
    public static final Emoji 水 = new Emoji(2, 128166, "水");
    public static final Emoji 吹气 = new Emoji(2, 128168, "吹气");
    public static final Emoji 肌肉 = new Emoji(2, 128170, "肌肉");
    public static final Emoji 邮箱 = new Emoji(2, 128235, "邮箱");
    public static final Emoji 火 = new Emoji(2, 128293, "火");
    public static final Emoji 呲牙2 = new Emoji(2, 128513, "呲牙");
    public static final Emoji 激动2 = new Emoji(2, 128514, "激动");
    public static final Emoji 高兴 = new Emoji(2, 128516, "高兴");
    public static final Emoji 嘿嘿 = new Emoji(2, 128522, "嘿嘿");
    public static final Emoji 羞涩 = new Emoji(2, 128524, "羞涩");
    public static final Emoji 哼哼 = new Emoji(2, 128527, "哼哼");
    public static final Emoji 不屑 = new Emoji(2, 128530, "不屑");
    public static final Emoji 汗2 = new Emoji(2, 128531, "汗");
    public static final Emoji 失落 = new Emoji(2, 128532, "失落");
    public static final Emoji 飞吻2 = new Emoji(2, 128536, "飞吻");
    public static final Emoji 亲亲2 = new Emoji(2, 128538, "亲亲");
    public static final Emoji 淘气 = new Emoji(2, 128540, "淘气");
    public static final Emoji 吐舌 = new Emoji(2, 128541, "吐舌");
    public static final Emoji 大哭2 = new Emoji(2, 128557, "大哭");
    public static final Emoji 紧张 = new Emoji(2, 128560, "紧张");
    public static final Emoji 瞪眼 = new Emoji(2, 128563, "瞪眼");

    private Integer id;
    /**
     * <table><thead><tr><th>值</th> <th>描述</th></tr></thead> <tbody><tr><td>1</td> <td>系统表情</td></tr> <tr><td>2</td> <td>emoji表情</td></tr></tbody></table>
     */
    private Integer type;

    /**
     * 不存在
     */
    @JSONField(serialize = false, deserialize = false)
    private String text;

    public Emoji(Integer type, Integer id, String text) {
        this.id = id;
        this.type = type;
        this.text = text;
        VALUES.add(this);
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public static Emoji valueOf(int type, int id) {
        for (Emoji value : VALUES) {
            if (value.type == type && value.id == (id)) return value;
        }
        return new Emoji(type, id, "未知表情");
    }

    public static Emoji valueOf(int id) {
        for (Emoji value : VALUES) {
            if (value.id == id) return value;
        }
        return new Emoji(1, id, "未知表情");
    }

    @Override
    public Result send(SenderAndCidMidGetter er) {
        return er.send(toString0());
    }

    @Override
    public String toString() {
        return String.format("[emoji(%s):%s]", getId(), getText());
    }

    public String toString0() {
        if (type == 2) return getText().split(" ")[0];
        return String.format("<emoji:%s>", getId());
    }
}

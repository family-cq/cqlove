package com.cqgzs.cqlove.magic;

/**
 * 18 个魔法系列（需求文档 5.1.1）。
 *
 * <p>顺序即“觉醒顺序”的默认参考：第 1 个觉醒的绑定 Z、第 2 个绑 X、第 3 个绑 C、第 4 个绑 V。
 * 每个系列都有一个代表色，用于聊天栏文字、HUD 与后续天赋界面的图标底色。</p>
 */
public enum MagicElement {

    THUNDER("thunder", "雷系", 0xFF8A5CFF),
    FIRE("fire", "火系", 0xFFFF5B3A),
    WIND("wind", "风系", 0xFF35D6C8),
    WATER("water", "水系", 0xFF3A8BFF),
    LIGHT("light", "光系", 0xFFFFD24A),
    EARTH("earth", "土系", 0xFFB07A3C),
    ICE("ice", "冰系", 0xFF9BE7FF),
    MIND("mind", "心灵系", 0xFFFF7AD9),
    POISON("poison", "毒系", 0xFF7BE04A),
    HEAL("heal", "治愈系", 0xFF6CFFB0),
    SUMMON("summon", "召唤系", 0xFFC08CFF),
    SOUND("sound", "音系", 0xFFFFB86B),
    SHADOW("shadow", "暗影系", 0xFF6B5B95),
    PLANT("plant", "植物系", 0xFF4CAF50),
    SPACE("space", "空间系", 0xFF8FA8FF),
    BLESS("bless", "祝福系", 0xFFFFF1A8),
    UNDEAD("undead", "亡灵系", 0xFFA0A0A0),
    CURSE("curse", "诅咒系", 0xFF9B2C6B);

    /** 存档/网络传输用的稳定 id，禁止改动 */
    private final String id;
    /** 中文显示名 */
    private final String display;
    /** 代表色（ARGB） */
    private final int color;

    MagicElement(String id, String display, int color) {
        this.id = id;
        this.display = display;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public int color() {
        return color;
    }

    /** 16x16 系列图标资源路径（图片放在 assets/cqlove/textures/gui/magic/element/） */
    public String iconPath() {
        return "textures/gui/magic/element/" + id + ".png";
    }

    /** 按 id 反查，找不到返回 null（存档兼容用） */
    public static MagicElement byId(String id) {
        for (MagicElement element : values()) {
            if (element.id.equals(id)) {
                return element;
            }
        }
        return null;
    }
}

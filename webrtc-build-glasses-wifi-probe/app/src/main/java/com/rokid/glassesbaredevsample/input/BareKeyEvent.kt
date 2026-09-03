package com.rokid.glassesbaredevsample.input

/**
 * Sample 统一 UI 语义。
 *
 * 事件定义见开发者文档「按键与佩戴和折叠」专章。
 * Sample 导航仅使用单指单击、单指双击、TouchPad 单指长按；镜腿长按仅 abort。
 *
 * - [Click]：`KEYCODE_ENTER`（TouchPad 单指单击）与 `ACTION_SPRITE_BUTTON_CLICK`（镜腿单击）等价。
 * - [DoubleClick]：`KEYCODE_BACK`（TouchPad 单指双击）→ Hub 进入 / 子页返回。
 * - [LongPress]：TouchPad 单指长按 · `KEYCODE_PROG_BLUE` 或 `ACTION_AI_START`。
 */
enum class BareKeyEvent {
    Click,
    DoubleClick,
    LongPress,
}

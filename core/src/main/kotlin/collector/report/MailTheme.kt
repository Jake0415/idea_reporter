package collector.report

/**
 * 메일 HTML 공통 꾸밈 — 디자인 토큰 "A 피드" 라이트 값(mockup/tokens.css, docs/design-tokens.md).
 *
 * 메일 클라이언트 호환을 위해 인라인 style + 표(table) 배치만 쓴다. 웹폰트·이미지·스크립트·rgba·CSS 변수 없음.
 * 다크 모드는 메일 앱의 자동 반전에 맡긴다(2026-11-23 결정). 값을 바꾸면 tokens.css 도 함께 고친다(MailThemeTest가 대조).
 */
object MailTheme {
    // 토큰 이름(tokens.css 의 --ic-이름) → 라이트 값
    val tokens: Map<String, String> = linkedMapOf(
        "accent" to "#0095F6",
        "accent-strong" to "#0077D9",
        "on-accent-strong" to "#FFFFFF",
        "link" to "#005591",
        "bg" to "#FFFFFF",
        "surface" to "#FFFFFF",
        "surface-muted" to "#F1F1F1",
        "text" to "#000000",
        "text-2" to "#474747",
        "text-3" to "#737373",
        "warning-bg" to "#FFF4D6",
        "warning-text" to "#6B4700",
        "success-bg" to "#E3F4E5",
        "success-text" to "#1E6B2E",
        "error-bg" to "#FDE7E6",
        "error-text" to "#A31F26",
        "info-bg" to "#E5F1FE",
        "info-text" to "#0058B0",
    )
    private fun t(name: String) = tokens.getValue(name)

    /** 테두리는 메일에서 rgba를 못 쓰는 곳이 있어 흰 바탕 위 불투명 값으로 바꾼 것(border = 검정 8%). */
    const val BORDER = "#EBEBEB"
    const val FONT = "system-ui,-apple-system,'Segoe UI',Roboto,'Malgun Gothic','Apple SD Gothic Neo',sans-serif"
    const val RADIUS_INNER = "5px"
    const val RADIUS_CONTAINER = "10px"

    enum class Tone(val bg: String, val fg: String) {
        WARNING(t("warning-bg"), t("warning-text")),
        SUCCESS(t("success-bg"), t("success-text")),
        ERROR(t("error-bg"), t("error-text")),
        INFO(t("info-bg"), t("info-text")),
        /** 좋은 신호 배지 = 정보와 같은 값 */
        HIGHLIGHT(t("info-bg"), t("info-text")),
        /** 분류 이름 같은 중립 토큰 */
        NEUTRAL(t("surface-muted"), t("text-2")),
        OUTLINE("transparent", t("text-2")),
    }

    fun document(title: String, body: String): String = buildString {
        append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>$title</title></head>\n")
        append("<body style=\"margin:0;padding:0;background:${t("bg")};-webkit-text-size-adjust:100%\">\n")
        append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background:${t("bg")}\">")
        append("<tr><td align=\"center\" style=\"padding:24px 16px\">\n")
        append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"max-width:640px\">")
        append("<tr><td style=\"font-family:$FONT;font-size:14px;line-height:1.5;color:${t("text")};text-align:left\">\n")
        append(body)
        append("</td></tr></table>\n</td></tr></table>\n</body></html>\n")
    }

    val h1 = "margin:0 0 4px;font-size:22px;line-height:1.3;font-weight:700;color:${t("text")}"
    val h2 = "margin:24px 0 8px;font-size:16px;line-height:1.4;font-weight:600;color:${t("text")}"
    val h3 = "margin:0 0 4px;font-size:16px;line-height:1.4;font-weight:600;color:${t("text")}"
    val p = "margin:0 0 8px"
    val meta = "margin:0 0 8px;font-size:12px;line-height:1.5;color:${t("text-2")}"
    val footer = "margin:24px 0 0;padding:12px 0 0;border-top:1px solid $BORDER;font-size:12px;line-height:1.5;color:${t("text-3")}"
    val link = "color:${t("link")};text-decoration:underline"
    val strong = "font-weight:600;color:${t("text")}"
    val muted = "background:${t("surface-muted")};border-radius:$RADIUS_CONTAINER;padding:12px 16px;margin:0 0 12px"

    /** 작은 배지(분류·좋은 신호). */
    fun pill(text: String, tone: Tone = Tone.NEUTRAL) =
        "<span style=\"display:inline-block;background:${tone.bg};color:${tone.fg};font-size:12px;line-height:18px;" +
            (if (tone == Tone.OUTLINE) "border:1px solid ${t("text-3")};" else "") +
            "font-weight:600;padding:0 6px;border-radius:$RADIUS_INNER;white-space:nowrap\">$text</span>"

    /** 안내 상자(실패·주의 등). 크기는 본문보다 작게 — 카드보다 먼저 눈에 띄지 않도록. */
    fun banner(text: String, tone: Tone = Tone.WARNING) =
        "<p style=\"margin:0 0 12px;background:${tone.bg};color:${tone.fg};font-size:12px;line-height:1.5;" +
            "padding:8px 12px;border-radius:$RADIUS_CONTAINER\">$text</p>"

    /** 테두리 상자(카드). 그림자 없음. */
    fun card(inner: String, id: String? = null) =
        "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"" +
            (id?.let { " id=\"$it\"" } ?: "") +
            " style=\"border:1px solid $BORDER;border-radius:$RADIUS_CONTAINER;border-collapse:separate;margin:0 0 12px\">" +
            "<tr><td style=\"padding:16px\">$inner</td></tr></table>"
}

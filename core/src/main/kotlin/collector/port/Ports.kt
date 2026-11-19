package collector.port

import collector.model.Item

/** 이미 본 글 기록. 앱=Room, PC=JSON 파일 */
interface SeenStore {
    /** 아직 보지 않은 항목만 돌려준다(출처 키 또는 정규화 URL이 같으면 본 것). */
    suspend fun filterUnseen(items: List<Item>): List<Item>
    suspend fun markSeen(items: List<Item>)
}

/** 비밀값 꺼내기. 앱=Android Keystore, PC·runner=환경 변수. 없으면 null */
fun interface SecretProvider {
    fun get(name: String): String?
}

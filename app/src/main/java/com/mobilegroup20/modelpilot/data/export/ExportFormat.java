package com.mobilegroup20.modelpilot.data.export;

/**
 * 导出格式。
 *
 * <p><b>两种格式的产物不一样，这是数据形状决定的，不是偷懒：</b>
 * <ul>
 *   <li>{@link #JSON} 是<b>一份文件</b>。对话天然是嵌套的（一条对话挂着消息和记忆），
 *       JSON 能原样表达，选到的范围都在同一个文件里；</li>
 *   <li>{@link #CSV} 是<b>一个 zip</b>，因为 CSV 一张表只能是一个文件，硬把对话和消息
 *       塞进一张表就得把对话信息在每一行上重复一遍（那种文件没法再当表用）。
 *       「一张表一个 csv，多个表打成一个包」也是各家用量导出的通行做法
 *       （DeepSeek 的用量导出返回的就是一个装 csv 的 zip，见 `docs/DATA_SOURCES.md`）。</li>
 * </ul>
 *
 * <p>所以界面上必须把这件事说清楚：选 CSV 得到的是 zip，不是 csv。用户选了 CSV
 * 却拿到一个打不开的 zip、又没人告诉他为什么，是这个功能最容易招骂的一处。
 */
public enum ExportFormat {

    /** 一份 JSON 文件（{@code .json}）。 */
    JSON,

    /** 一张表一个 csv，打成一个 zip（{@code .zip}，里面含 {@code meta.json}）。 */
    CSV
}

package com.example.harleyapp.data

import com.example.harleyapp.model.ChineseReadingTopic
import com.example.harleyapp.model.ChineseWritingMission
import com.example.harleyapp.model.PrimarySchoolGrade
import com.example.harleyapp.model.isChineseReadingTopicSuitable

/**
 * 提供按年级递进的原创写作训练和安全阅读主题白名单。
 *
 * 使用方法：
 * 页面通过[writingMissionsFor]取得当前年级训练，通过[readingTopicsFor]取得适龄阅读主题。写作
 * 内容不复制作文书范文；阅读正文仅由仓库根据这里固定的百科标题联网读取，用户输入不能改变网址。
 */
object ChineseGrowthCatalog {

    private val writingMissions = listOf(
        mission(PrimarySchoolGrade.GRADE_ONE, 1, "把一句话写完整", "写清谁、在哪里、做什么", "观察一张家庭照片，写一句完整的话。", listOf("谁：照片里最主要的人", "哪里：事情发生的地方", "做什么：看得见的动作"), listOf("句子有谁", "句子有动作", "句末有标点")),
        mission(PrimarySchoolGrade.GRADE_ONE, 2, "给句子加颜色", "用颜色、大小和形状把事物写具体", "选择书包里的一件物品，写两句话介绍它。", listOf("先看清它是什么", "第一句：说出物品名称", "第二句：写颜色、大小或形状"), listOf("至少用了一个颜色词", "没有重复同一个词", "读起来通顺")),
        mission(PrimarySchoolGrade.GRADE_ONE, 3, "按顺序写三句", "使用先、再、最后表示顺序", "写一写你放学回家后做的三件事。", listOf("先……", "再……", "最后……"), listOf("顺序没有颠倒", "每句只写一件事", "使用了顺序词")),

        mission(PrimarySchoolGrade.GRADE_TWO, 1, "让动作动起来", "用连续动作代替笼统的“做了”", "观察家人做饭或整理房间，写四句话。", listOf("开始前在做什么", "第一个动作", "接着的动作", "结果怎样"), listOf("至少写了三个不同动作", "动作顺序合理", "写出了结果")),
        mission(PrimarySchoolGrade.GRADE_TWO, 2, "写一则小日记", "交代日期、天气、事情和心情", "记录今天最想记住的一件小事。", listOf("日期和天气", "事情发生在哪里", "事情怎样发生", "我的心情或发现"), listOf("四个要素齐全", "事情是真实经历", "结尾写了感受")),
        mission(PrimarySchoolGrade.GRADE_TWO, 3, "把对话写清楚", "用人物说的话表现事情", "写一次你向同学借东西或帮助同学的经历。", listOf("事情为什么发生", "我说了什么", "对方说了什么", "事情结果"), listOf("说话的人清楚", "使用了冒号和引号", "对话推动了事情")),

        mission(PrimarySchoolGrade.GRADE_THREE, 1, "围绕一句中心句", "一段话只表达一个主要意思", "以“课间十分钟真热闹”为中心句写一段话。", listOf("先写中心句", "选择三个不同人物或活动", "按看到的顺序写", "用一句话收束"), listOf("每句话都围绕热闹", "有三个具体场景", "不少于120字")),
        mission(PrimarySchoolGrade.GRADE_THREE, 2, "连续观察一种植物", "记录变化并比较前后不同", "连续三天观察一片叶子或一盆植物。", listOf("第一天的颜色和形状", "第二天出现的变化", "第三天的新发现", "我猜变化的原因"), listOf("有时间变化", "使用了比较词", "事实和猜想分开")),
        mission(PrimarySchoolGrade.GRADE_THREE, 3, "写清一件事情", "用起因、经过、结果搭好叙事骨架", "写一次你成功解决小困难的经历。", listOf("起因：遇到了什么", "经过：尝试了哪些办法", "转折：最关键的一步", "结果和感受"), listOf("事情完整", "经过最详细", "感受来自真实经历")),

        mission(PrimarySchoolGrade.GRADE_FOUR, 1, "用细节写人物", "用动作、语言和神态表现特点", "写一位做事很认真的人。", listOf("用一件事表现认真", "抓住一个连续动作", "记录一句有特点的话", "补充当时的神态"), listOf("没有只写“他很认真”", "至少两种细节描写", "人物特点鲜明")),
        mission(PrimarySchoolGrade.GRADE_FOUR, 2, "移步换景写景物", "按位置变化组织景物", "选择校园、公园或小区的一条路线写景。", listOf("入口看到什么", "向前走景物怎样变化", "停留处重点观察", "离开时的整体感受"), listOf("路线清楚", "重点景物写得最详细", "使用方位词")),
        mission(PrimarySchoolGrade.GRADE_FOUR, 3, "修改让文章更有力", "删重复、换准确词、补关键细节", "找出自己以前的一篇习作，完成一次三步修改。", listOf("划掉重复句", "圈出可以更准确的动词", "在最重要处补一个细节", "大声朗读再调整"), listOf("能看出修改痕迹", "动词更准确", "句子更简洁")),

        mission(PrimarySchoolGrade.GRADE_FIVE, 1, "把关键时刻放慢", "用小动作和心理变化写出画面", "写一次等待结果、参加比赛或登台前的经历。", listOf("事情发生前的期待", "关键时刻的动作", "心里想到什么", "结果出现后的变化"), listOf("关键时刻篇幅最多", "心理和动作相互照应", "少用空泛形容词")),
        mission(PrimarySchoolGrade.GRADE_FIVE, 2, "写一篇简单说明文", "用分类、比较和数字说明事物", "介绍一种熟悉的动物、植物或生活用品。", listOf("先说明它是什么", "按两到三个方面分类介绍", "加入一个数字或比较", "说明它和生活的关系"), listOf("信息准确", "说明顺序清楚", "至少用了两种说明方法")),
        mission(PrimarySchoolGrade.GRADE_FIVE, 3, "写有证据的读后感", "感受要联系文章细节和自己的经历", "选择最近读过的一篇文章，写一篇读后感。", listOf("用两三句概括内容", "指出最触动我的细节", "解释为什么触动", "联系自己的经历或行动"), listOf("复述不超过全文三分之一", "引用了具体细节", "有自己的思考")),

        mission(PrimarySchoolGrade.GRADE_SIX, 1, "先确定立意再选材", "围绕一个中心筛选最有表现力的材料", "以“这件事让我长大”为题列提纲并完成习作。", listOf("写下想表达的成长", "列出三个候选事件", "选择冲突和变化最清楚的一件", "安排详略"), listOf("所有材料服务同一中心", "变化前后形成对比", "详略安排明显")),
        mission(PrimarySchoolGrade.GRADE_SIX, 2, "让开头结尾互相照应", "首尾共同突出文章中心", "修改一篇旧习作，为它重新设计开头和结尾。", listOf("开头留下人物、景物或问题线索", "正文让线索出现变化", "结尾回应同一线索", "检查是否点明中心"), listOf("首尾能找到共同线索", "结尾没有简单重复开头", "中心自然呈现")),
        mission(PrimarySchoolGrade.GRADE_SIX, 3, "用事实表达观点", "观点、理由和例子组成完整论证", "围绕“小学生是否应该每天做家务”写一篇短文。", listOf("明确写出观点", "给出两个不同角度的理由", "每个理由配一个事实或例子", "回应一种不同意见"), listOf("观点明确", "理由不重复", "例子能证明理由", "语言尊重不同意见"))
    )

    private val readingTopics = listOf(
        topic("panda", 1, 2, "动物", "大熊猫怎样生活", "大熊猫", "大熊猫主要生活在中国山区的竹林中。它们看起来行动缓慢，却很会爬树，也能游泳。竹子能量不高，所以大熊猫一天要花很长时间进食。", "大熊猫的生活环境和食物有什么关系？", "用三句话介绍一种你熟悉的动物。"),
        topic("rainbow", 1, 2, "自然", "雨后为什么有彩虹", "彩虹", "阳光进入小水滴后会发生折射和反射，原本看起来白色的光被分成多种颜色。观察者、太阳和水滴的位置合适时，我们就能看到弧形彩虹。", "看到彩虹需要哪些条件？", "按颜色或形状写一段雨后景象。"),
        topic("bee", 1, 3, "动物", "蜜蜂的分工", "蜜蜂", "一个蜂群里有蜂王、雄蜂和许多工蜂。工蜂会采集花蜜、照料幼虫和保卫蜂巢。它们用动作传递食物方向的信息。", "蜂群里不同成员分别做什么？", "用“先、再、最后”写蜜蜂采蜜。"),
        topic("moon", 1, 3, "宇宙", "我们看到的月球", "月球", "月球是地球的天然卫星。它本身不会发光，我们看到的亮光来自太阳。月球绕地球运行，所以从地球上看到的明亮部分会有规律地变化。", "为什么月亮的形状看起来会变化？", "把月亮比作一种熟悉的事物并说明理由。"),
        topic("spring_festival", 1, 4, "文化", "春节里的文化", "春节", "春节是中国重要的传统节日。不同地方有贴春联、吃年夜饭、拜年等习俗。习俗会随时代和地区变化，但团聚与祝福一直是重要主题。", "你家的一项春节习俗表达了什么心愿？", "记录一项家庭节日习俗的过程。"),
        topic("great_wall", 2, 4, "历史", "长城不只是一堵墙", "长城", "长城由不同时期修建的城墙、关隘、烽火台等组成，并不是一条完全连续的墙。它与古代交通、防御和边疆生活密切相关。", "长城由哪些部分组成？", "选择一个建筑，从外形和用途两方面介绍。"),
        topic("migration", 2, 4, "动物", "动物为什么迁徙", "迁徙", "许多动物会在季节变化时有规律地移动。它们可能为了寻找食物、繁殖地点或更合适的温度而迁徙。迁徙路线常常跨越很远的距离。", "动物迁徙可能有哪些原因？", "以一只候鸟的口吻写一段旅程。"),
        topic("solar_system", 3, 5, "宇宙", "太阳系是一座怎样的家园", "太阳系", "太阳系以太阳为中心，包括行星、矮行星、小行星和彗星等天体。地球只是其中一颗行星。比较大小、距离和运动方式，能帮助我们理解这个巨大系统。", "文章可以从哪些方面比较行星？", "使用两个比较句介绍地球和另一颗行星。"),
        topic("dujiangyan", 3, 5, "工程", "两千多年的都江堰", "都江堰", "都江堰利用地形和水流规律分水、排沙、控制水量，没有把江水简单堵住。它长期服务成都平原，体现了古人因地制宜解决问题的智慧。", "都江堰解决问题的方法有什么特别？", "按“问题—办法—效果”介绍一个生活工具。"),
        topic("paper", 3, 5, "发明", "纸改变了信息传播", "造纸术", "纸便于书写、携带和保存，使知识传播更加方便。造纸方法经历了长期改进，不是某一天突然完成的单一发明。理解发明要关注材料、工艺和社会需要。", "造纸方法为什么会不断改进？", "说明一种发明给生活带来的三个变化。"),
        topic("forbidden_city", 3, 6, "文化", "故宫里的建筑秩序", "北京故宫", "北京故宫由许多宫殿、院落和通道组成。建筑在中轴线上形成清楚层次，色彩、屋顶和空间安排都承载着历史文化信息。", "故宫建筑怎样表现出层次和秩序？", "按游览路线描写一处熟悉的建筑群。"),
        topic("coral_reef", 3, 6, "生态", "热闹的珊瑚礁", "珊瑚礁", "珊瑚礁由珊瑚虫长期生长形成，为许多海洋生物提供食物和栖息地。水温变化、污染等因素会影响珊瑚健康，也会改变整个生态系统。", "珊瑚礁和其他生物之间有什么联系？", "用因果关系写一段保护生态环境的建议。"),
        topic("grand_canal", 4, 6, "历史", "大运河连接了什么", "京杭大运河", "京杭大运河跨越多个水系和地区，长期承担运输、交流等功能。研究运河不能只看长度，还要观察它怎样影响沿岸城市、物产和文化。", "运河给沿岸地区带来了哪些联系？", "用地点变化组织一段运河旅行说明。"),
        topic("silk_road", 4, 6, "交流", "丝绸之路上的相遇", "丝绸之路", "丝绸之路不是一条固定道路，而是连接亚洲、欧洲和非洲部分地区的交通网络。货物、技术、艺术和观念都曾沿着这些路线交流。", "为什么说丝绸之路不只运输丝绸？", "选择一种物品，想象它跨地区旅行的故事。"),
        topic("oracle_bone", 4, 6, "文字", "甲骨文记录了什么", "甲骨文", "甲骨文是刻写在龟甲和兽骨上的古文字材料。它记录了古人的占卜和生活信息，也帮助研究者了解汉字演变与商代社会。", "甲骨文为什么既是文字资料也是历史资料？", "选一个汉字，观察字形并写出你的联想。"),
        topic("mogao", 5, 6, "艺术", "敦煌莫高窟的多重价值", "莫高窟", "莫高窟保存了跨越多个时期的洞窟、壁画和彩塑。不同人物、服饰和故事反映了艺术变化，也记录了古代交通与文化交流。", "研究莫高窟可以获得哪些不同方面的信息？", "从色彩、人物和故事三个角度描写一幅壁画。"),
        topic("hybrid_rice", 5, 6, "科学", "杂交水稻与粮食", "杂交水稻", "杂交水稻利用不同水稻材料的遗传差异培育新品种。育种需要长期试验、记录和筛选，也要综合考虑产量、品质和环境适应能力。", "科学育种为什么需要反复试验？", "用“目标—过程—结果”介绍一次科学实验。"),
        topic("beidou", 5, 6, "科技", "北斗怎样帮助定位", "北斗卫星导航系统", "卫星导航系统通过多颗卫星发送时间和位置信息，接收设备计算自己所在的位置。它可以用于交通、测绘、救援和农业等领域。", "定位系统为什么需要多颗卫星共同工作？", "选择一个应用场景，说明北斗能解决什么问题。")
    )

    /**
     * 取得当前年级全部写作训练。
     *
     * @param grade 用户选择的年级。
     * @return 按难度递进排列的原创训练；每个年级固定三项。
     */
    fun writingMissionsFor(grade: PrimarySchoolGrade): List<ChineseWritingMission> {
        return writingMissions.filter { mission -> mission.grade == grade }
    }

    /**
     * 取得当前年级适合的白名单阅读主题。
     *
     * @param grade 用户选择的年级。
     * @return 保持目录顺序的适龄主题，不包含随机网页或用户可控网址。
     */
    fun readingTopicsFor(grade: PrimarySchoolGrade): List<ChineseReadingTopic> {
        return readingTopics.filter { topic -> isChineseReadingTopicSuitable(topic, grade) }
    }

    /** @return 全部写作训练，供完整性测试使用。 */
    fun allWritingMissions(): List<ChineseWritingMission> = writingMissions

    /** @return 全部阅读主题白名单，供完整性测试和仓库校验使用。 */
    fun allReadingTopics(): List<ChineseReadingTopic> = readingTopics

    /** 创建结构一致的年级写作任务。 */
    private fun mission(
        grade: PrimarySchoolGrade,
        index: Int,
        title: String,
        focus: String,
        prompt: String,
        outline: List<String>,
        checklist: List<String>
    ): ChineseWritingMission {
        return ChineseWritingMission(
            id = "chinese_writing_${grade.gradeNumber}_$index",
            grade = grade,
            title = title,
            focus = focus,
            methodSteps = outline.mapIndexed { stepIndex, item -> "第${stepIndex + 1}步：$item" },
            prompt = prompt,
            outline = outline,
            checklist = checklist
        )
    }

    /** 创建只允许固定百科标题的阅读主题。 */
    private fun topic(
        id: String,
        minGrade: Int,
        maxGrade: Int,
        category: String,
        title: String,
        wikipediaTitle: String,
        offlineGuide: String,
        observationQuestion: String,
        writingChallenge: String
    ): ChineseReadingTopic {
        return ChineseReadingTopic(
            id = "chinese_reading_$id",
            minGrade = minGrade,
            maxGrade = maxGrade,
            category = category,
            title = title,
            wikipediaTitle = wikipediaTitle,
            offlineGuide = offlineGuide,
            observationQuestion = observationQuestion,
            writingChallenge = writingChallenge
        )
    }
}

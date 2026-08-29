package com.example.harleyapp.data

/**
 * 随App安装的单词静态内容，不包含任何用户进度。
 *
 * @param id 跨版本保持稳定的进度标识。
 * @param word 英文单词。
 * @param meaningZh 中文释义。
 * @param exampleEn 简短英文例句。
 * @param exampleZh 例句的中文翻译。
 */
internal data class BundledEnglishWord(
    val id: String,
    val word: String,
    val meaningZh: String,
    val exampleEn: String,
    val exampleZh: String
)

/**
 * 取得首版完全离线的常用英语词库。
 *
 * 使用方法：
 * 仅由EnglishWordRepository加载一次。所有释义和例句均直接编译进APK，安装后无需下载文件、
 * 无需注册账号，也不会因为网络不可用而缺少内容。
 *
 * @return 按固定学习顺序排列的122个常用单词。
 */
internal fun loadBundledEnglishWords(): List<BundledEnglishWord> {
    return listOf(
        word("ability", "能力；本领", "She has the ability to learn quickly.", "她有快速学习的能力。"),
        word("accept", "接受；同意", "Please accept my sincere thanks.", "请接受我真诚的感谢。"),
        word("achieve", "实现；达到", "Small steps help us achieve big goals.", "小步前进能帮助我们实现大目标。"),
        word("active", "活跃的；积极的", "He stays active by walking every morning.", "他每天早晨散步来保持活力。"),
        word("advice", "建议；忠告", "Her advice made the choice easier.", "她的建议让这个选择更容易。"),
        word("afford", "负担得起；抽得出", "I cannot afford a new phone this month.", "这个月我买不起新手机。"),
        word("agree", "同意；赞成", "We agree on the main idea.", "我们对主要想法意见一致。"),
        word("allow", "允许；准许", "The teacher allows us to ask questions.", "老师允许我们提问。"),
        word("answer", "回答；答案", "Can you answer this simple question?", "你能回答这个简单的问题吗？"),
        word("appear", "出现；看起来", "A rainbow appeared after the rain.", "雨后出现了一道彩虹。"),
        word("apply", "申请；应用", "I will apply for the course today.", "我今天会申请这门课程。"),
        word("arrive", "到达", "Please call me when you arrive home.", "到家时请给我打电话。"),
        word("avoid", "避免；避开", "Try to avoid using your phone while driving.", "开车时尽量避免使用手机。"),
        word("balance", "平衡；均衡", "Good health needs a balance of work and rest.", "健康需要劳逸平衡。"),
        word("believe", "相信；认为", "I believe you can finish the task.", "我相信你能完成这项任务。"),
        word("borrow", "借入", "May I borrow your dictionary for a day?", "我可以借用你的词典一天吗？"),
        word("brave", "勇敢的", "She was brave enough to speak first.", "她勇敢地第一个发言。"),
        word("build", "建造；培养", "Reading helps build a strong vocabulary.", "阅读有助于建立丰富的词汇量。"),
        word("calm", "平静的；使平静", "Take a deep breath and stay calm.", "深呼吸并保持冷静。"),
        word("careful", "小心的；仔细的", "Be careful when you cross the street.", "过马路时要小心。"),
        word("change", "改变；变化", "A new habit can change your daily life.", "一个新习惯可以改变你的日常生活。"),
        word("choose", "选择", "You can choose the book you like.", "你可以选择自己喜欢的书。"),
        word("clear", "清楚的；清理", "Her explanation was short and clear.", "她的解释简短而清楚。"),
        word("collect", "收集；收藏", "We collect useful words in a notebook.", "我们把有用的单词收集在笔记本里。"),
        word("comfortable", "舒适的；自在的", "These shoes are light and comfortable.", "这双鞋轻便又舒适。"),
        word("communicate", "沟通；交流", "Good teams communicate with each other.", "优秀的团队会彼此沟通。"),
        word("compare", "比较；对比", "Compare the two plans before you decide.", "决定前比较一下两个方案。"),
        word("complete", "完成；完整的", "Please complete the form before Friday.", "请在周五前填完表格。"),
        word("confident", "自信的", "Practice makes me more confident in English.", "练习让我对英语更有信心。"),
        word("consider", "考虑；认为", "Please consider all the possible results.", "请考虑所有可能的结果。"),
        word("continue", "继续", "She continued reading after dinner.", "晚饭后她继续阅读。"),
        word("create", "创造；创建", "We can create a simple study plan.", "我们可以制定一个简单的学习计划。"),
        word("decide", "决定", "I decided to exercise every day.", "我决定每天锻炼。"),
        word("describe", "描述", "Can you describe the place in English?", "你能用英语描述这个地方吗？"),
        word("develop", "发展；培养", "Daily reading develops good language skills.", "每日阅读能培养良好的语言能力。"),
        word("different", "不同的", "People may have different opinions.", "人们可能有不同的看法。"),
        word("difficult", "困难的", "The test was difficult but useful.", "这次测试很难，但很有用。"),
        word("discover", "发现", "We discovered a quiet park nearby.", "我们发现附近有一个安静的公园。"),
        word("discuss", "讨论", "Let us discuss the problem after lunch.", "让我们午饭后讨论这个问题。"),
        word("dream", "梦想；做梦", "Her dream is to travel around the world.", "她的梦想是环游世界。"),
        word("early", "早的；提前", "I usually wake up early on weekdays.", "工作日我通常起得很早。"),
        word("effort", "努力", "Your effort will bring better results.", "你的努力会带来更好的结果。"),
        word("encourage", "鼓励", "My friends encourage me to keep learning.", "朋友们鼓励我继续学习。"),
        word("enough", "足够的", "Do we have enough time to finish?", "我们有足够的时间完成吗？"),
        word("example", "例子；榜样", "This sentence is a useful example.", "这个句子是一个有用的例子。"),
        word("experience", "经验；经历", "Travel is a valuable learning experience.", "旅行是一次宝贵的学习经历。"),
        word("explain", "解释；说明", "Could you explain the rule again?", "你能再解释一次这条规则吗？"),
        word("family", "家庭；家人", "My family eats dinner together.", "我的家人一起吃晚饭。"),
        word("focus", "专注；焦点", "Turn off notifications and focus on your work.", "关掉通知，专心工作。"),
        word("follow", "跟随；遵循", "Follow the steps on the screen.", "按照屏幕上的步骤操作。"),
        word("friendly", "友好的", "The people in this town are very friendly.", "这个镇上的人非常友好。"),
        word("future", "未来", "Learning today prepares you for the future.", "今天的学习是在为未来做准备。"),
        word("goal", "目标", "Write down one clear goal for this week.", "写下本周的一个明确目标。"),
        word("grow", "成长；增长", "Plants need light and water to grow.", "植物生长需要阳光和水。"),
        word("habit", "习惯", "Reviewing words daily is a useful habit.", "每天复习单词是一个好习惯。"),
        word("happen", "发生", "Mistakes happen when we learn something new.", "学习新事物时难免会犯错。"),
        word("healthy", "健康的", "A healthy breakfast gives you energy.", "健康的早餐能给你能量。"),
        word("helpful", "有帮助的", "The map was helpful during our trip.", "这张地图在旅行中很有帮助。"),
        word("honest", "诚实的", "Please give me an honest answer.", "请给我一个诚实的回答。"),
        word("improve", "改善；提高", "Listening every day can improve pronunciation.", "每天听英语可以改善发音。"),
        word("include", "包括；包含", "The price includes breakfast and parking.", "这个价格包含早餐和停车费。"),
        word("interest", "兴趣；使感兴趣", "Music is one of her main interests.", "音乐是她的主要兴趣之一。"),
        word("invite", "邀请", "We invited our neighbors to dinner.", "我们邀请邻居来吃晚饭。"),
        word("journey", "旅程", "The train journey took three hours.", "这段火车旅程用了三个小时。"),
        word("keep", "保持；保留", "Keep this note for future use.", "把这张便条留着以后用。"),
        word("knowledge", "知识", "Books give us knowledge and new ideas.", "书籍带给我们知识和新想法。"),
        word("language", "语言", "English is useful as an international language.", "英语是一门有用的国际语言。"),
        word("learn", "学习；学会", "We learn faster by using new words.", "通过使用新单词，我们学得更快。"),
        word("listen", "听；倾听", "Listen carefully to the whole sentence.", "仔细听完整个句子。"),
        word("manage", "管理；设法做到", "She manages her time very well.", "她很善于管理时间。"),
        word("meaning", "意思；意义", "Can you guess the meaning from the sentence?", "你能从句子中猜出意思吗？"),
        word("mistake", "错误", "Every mistake is a chance to learn.", "每个错误都是一次学习机会。"),
        word("necessary", "必要的", "Sleep is necessary for good health.", "睡眠对健康是必要的。"),
        word("notice", "注意到；通知", "Did you notice the change in his voice?", "你注意到他声音的变化了吗？"),
        word("offer", "提供；提议", "They offered me a cup of tea.", "他们给了我一杯茶。"),
        word("opportunity", "机会", "This job is a good opportunity to grow.", "这份工作是一个成长的好机会。"),
        word("organize", "组织；整理", "I organize my desk every Friday.", "我每周五整理书桌。"),
        word("patient", "有耐心的；病人", "Be patient with yourself while learning.", "学习时要对自己有耐心。"),
        word("practice", "练习；实践", "Ten minutes of practice is better than none.", "练习十分钟也比不练好。"),
        word("prepare", "准备", "We prepared everything the night before.", "我们前一天晚上准备好了一切。"),
        word("problem", "问题；难题", "Let us solve one problem at a time.", "让我们一次解决一个问题。"),
        word("promise", "承诺；答应", "I promise to return the book tomorrow.", "我答应明天还书。"),
        word("protect", "保护", "A strong password helps protect your account.", "强密码有助于保护你的账号。"),
        word("question", "问题；提问", "Ask a question when something is unclear.", "有不清楚的地方就提问。"),
        word("quiet", "安静的", "The library is a quiet place to study.", "图书馆是安静的学习场所。"),
        word("reach", "到达；达到", "We reached the station before noon.", "我们中午前到达了车站。"),
        word("ready", "准备好的", "Are you ready to start the lesson?", "你准备好开始上课了吗？"),
        word("remember", "记得；记住", "Remember to review these words tomorrow.", "记得明天复习这些单词。"),
        word("repeat", "重复", "Please repeat the sentence more slowly.", "请更慢地重复这个句子。"),
        word("respect", "尊重", "We should respect different cultures.", "我们应该尊重不同的文化。"),
        word("result", "结果", "Regular practice brings better results.", "经常练习会带来更好的结果。"),
        word("save", "保存；节省", "Save your work before closing the app.", "关闭应用前保存你的工作。"),
        word("share", "分享；共同拥有", "She shared her notes with the class.", "她与全班分享了笔记。"),
        word("simple", "简单的", "Start with a simple sentence.", "从一个简单的句子开始。"),
        word("skill", "技能；技巧", "Speaking is a skill that needs practice.", "口语是一项需要练习的技能。"),
        word("solve", "解决", "Working together can solve the problem.", "一起合作可以解决问题。"),
        word("speak", "说；讲", "Try to speak English for five minutes.", "试着说五分钟英语。"),
        word("special", "特别的；特殊的", "Today is a special day for our family.", "今天对我们家来说是个特别的日子。"),
        word("spend", "花费；度过", "I spend half an hour reading each night.", "我每晚花半小时阅读。"),
        word("start", "开始；出发", "It is never too late to start learning.", "开始学习永远不会太晚。"),
        word("study", "学习；研究", "She studies English on the train.", "她在火车上学英语。"),
        word("support", "支持；支撑", "My family supports my decision.", "我的家人支持我的决定。"),
        word("surprise", "惊喜；使惊讶", "The good news was a pleasant surprise.", "这个好消息是一个令人愉快的惊喜。"),
        word("teach", "教；教会", "Can you teach me how to use this tool?", "你能教我如何使用这个工具吗？"),
        word("together", "一起；共同", "We learn better when we practice together.", "一起练习时我们学得更好。"),
        word("understand", "理解；明白", "I understand the idea but need more practice.", "我理解这个想法，但还需要多练习。"),
        word("useful", "有用的", "This phrase is useful when you travel.", "这个短语在旅行时很有用。"),
        word("value", "价值；重视", "We value your time and honest feedback.", "我们重视你的时间和真诚反馈。"),
        word("visit", "参观；拜访", "We plan to visit the museum on Sunday.", "我们计划周日参观博物馆。"),
        word("wait", "等待", "Please wait here for a few minutes.", "请在这里等几分钟。"),
        word("welcome", "欢迎；受欢迎的", "You are always welcome in our home.", "我们家随时欢迎你。"),
        word("wise", "明智的；有智慧的", "It is wise to check the details first.", "先核对细节是明智的。"),
        word("wonder", "想知道；惊叹", "I wonder what we will learn next.", "我想知道接下来会学什么。"),
        word("work", "工作；起作用", "This method works well for me.", "这个方法对我很有效。"),
        word("worry", "担心", "Do not worry about making small mistakes.", "不要担心犯小错误。"),
        word("write", "写；书写", "Write one sentence with the new word.", "用这个新单词写一个句子。"),
        word("young", "年轻的；年幼的", "She started learning music at a young age.", "她很小就开始学习音乐。"),
        word("courage", "勇气", "It takes courage to try something new.", "尝试新事物需要勇气。"),
        word("curious", "好奇的", "Curious learners often ask good questions.", "好奇的学习者经常提出好问题。"),
        word("daily", "每日的；日常的", "Make English part of your daily routine.", "让英语成为你日常生活的一部分。"),
        word("energy", "能量；精力", "A short walk gives me more energy.", "短暂散步让我更有精力。"),
        word("enjoy", "享受；喜欢", "I enjoy learning through short stories.", "我喜欢通过短篇故事学习。")
    )
}

/**
 * 用英文单词本身作为稳定标识创建一条内置内容。
 *
 * @param spelling 英文拼写，同时用作稳定标识和页面单词。
 * @param meaningZh 中文释义。
 * @param exampleEn 英文例句。
 * @param exampleZh 中文例句。
 * @return 可由仓库直接合并进度的静态单词对象。
 */
private fun word(
    spelling: String,
    meaningZh: String,
    exampleEn: String,
    exampleZh: String
): BundledEnglishWord {
    return BundledEnglishWord(
        id = spelling,
        word = spelling,
        meaningZh = meaningZh,
        exampleEn = exampleEn,
        exampleZh = exampleZh
    )
}

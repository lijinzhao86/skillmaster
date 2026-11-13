package com.skillmasterai.usecase;

import com.skillmasterai.modules.feedback.FeedbackConsent;
import org.springframework.stereotype.Component;

/**
 * The notice a person is shown before a feedback document leaves their machine (v64).
 *
 * <p>The text lives here rather than in the CLI so that a policy change reaches everyone without a
 * CLI release. It is deliberately short: the full clause is in the privacy policy, and this is the
 * sentence the terminal can show at the moment of the decision.
 */
@Component
public class ReadFeedbackConsentUseCase {

    /** Printed verbatim by {@code skmr feedback send} when it previews. */
    public static final String NOTICE = """
            提交反馈表示你已阅读并同意《隐私政策》中「你主动提交的反馈」一节。
            将发送：你写下的完整文档、你的账号标识、提交时间；关联 skill 与诊断字段只在显式要求时附带。
            如果你加了附件，将一并发送附件文件、文件名、类型、大小与摘要；图片或视频不会被自动扫描。
            你可以随时运行 skmr feedback delete <回执> --yes 撤回并删除（附件一并删除）。""";

    private final LegalTextVersions legal;

    public ReadFeedbackConsentUseCase(LegalTextVersions legal) {
        this.legal = legal;
    }

    public FeedbackConsent consent() {
        return new FeedbackConsent(legal.privacy(), NOTICE);
    }
}

package com.wh.baldr.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import com.wh.baldr.core.analyzer.AIDiagnosis;
import com.wh.baldr.core.collector.ProfilerCollector;
import com.wh.baldr.core.model.DiagnosisResult;
import com.wh.baldr.core.model.ProfileReport;
import com.wh.baldr.core.parser.BaldrProfileParser;
import com.wh.baldr.core.report.MarkdownReportGenerator;
import com.wh.baldr.core.report.ReportGenerator;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Baldr 命令行入口。
 * 用法示例：{@code java -jar baldr.jar --pid 12345 --duration 30 --event cpu}
 *
 * @author rubant
 * @date 2026-08-14
 */
@Command(name = "baldr",
        mixinStandardHelpOptions = true,
        version = "baldr 1.0.0",
        description = "采集目标进程性能数据，经大模型分析生成性能报告")
public class BaldrCli implements Callable<Integer> {

    @Option(names = {"-p", "--pid"}, required = true, description = "目标 Java 进程 PID")
    private int pid;

    @Option(names = {"-d", "--duration"}, description = "采样时长（秒），默认 30")
    private int duration = 30;

    @Option(names = {"-e", "--event"}, description = "采样事件：cpu / alloc / lock，默认 cpu")
    private String event = "cpu";

    @Option(names = {"-o", "--output"}, description = "报告输出文件路径，默认打印到控制台")
    private String output;

    @Option(names = {"--local"}, description = "使用本地大模型；默认 false，即使用云端 provider")
    private boolean useLocal = false;

    @Option(names = {"--provider"}, description = "云端大模型 provider：deepseek / doubao / joyai，默认 deepseek")
    private String provider;

    @Option(names = {"--api-key"}, description = "API Key，默认读取对应 provider 的环境变量（如 DEEPSEEK_API_KEY / JOYAI_API_KEY）")
    private String apiKey;

    @Option(names = {"--endpoint"}, description = "自定义 API endpoint，默认使用 provider 内置地址")
    private String endpoint;

    @Option(names = {"--model"}, description = "模型名，默认使用 provider 内置默认模型")
    private String model;

    private final ProfilerCollector collector = new ProfilerCollector();
    private final BaldrProfileParser parser = new BaldrProfileParser();
    private final ReportGenerator reportGenerator = new MarkdownReportGenerator();

    @Override
    public Integer call() throws Exception {
        // 1. 采集
        String profileFile = collector.collect(pid, duration, event);

        // 2. 解析
        String content = new String(
                Files.readAllBytes(Paths.get(profileFile)), StandardCharsets.UTF_8);
        ProfileReport report = parser.parse(content);

        // 3. AI 分析
        String prompt = AIDiagnosis.buildPrompt(report, null);
        DiagnosisResult result;
        try {
            result = AIDiagnosis.diagnose(prompt, useLocal, provider, apiKey, endpoint, model);
        } catch (Exception e) {
            // AI 分析失败不应导致采样结果丢失，降级为仅输出热点报告
            System.err.println("AI 诊断失败，仅输出性能数据: " + e.getMessage());
            result = null;
        }

        // 4. 生成报告
        String rendered = reportGenerator.render(report, result);

        // 5. 输出
        if (output != null && !output.trim().isEmpty()) {
            Files.write(Paths.get(output), rendered.getBytes(StandardCharsets.UTF_8));
            System.out.println("报告已生成: " + output);
        } else {
            System.out.println(rendered);
        }
        return 0;
    }

    public static void main(String[] args) {
        // 自举：以干净的 JVM 参数重启进程，消除 native 层警告
        // （ByteBuddy 动态 Agent 加载、CDS Sharing、async-profiler framebuf）。
        // 无论 IDE Run / java -jar / 脚本启动，都能得到干净输出。
        BaldrLauncher.relaunchIfNeeded(args);

        int exitCode = new CommandLine(new BaldrCli()).execute(args);
        System.exit(exitCode);
    }
}
package com.example.LAGO.service;

import com.example.LAGO.domain.*;
import com.example.LAGO.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyQuizService {

    private final DailyQuizScheduleRepository dailyQuizScheduleRepository;
    private final QuizRepository quizRepository;
    private final DailySolvedRepository dailySolvedRepository;
    private final KnowTermRepository knowTermRepository;
    private final AccountService accountService;

    public Quiz getTodayQuiz() {
        LocalDate today = LocalDate.now();
        
        DailyQuizSchedule schedule = dailyQuizScheduleRepository.findByQuizDate(today)
                .orElseThrow(() -> new RuntimeException("No daily quiz scheduled for today"));

        return quizRepository.findById(schedule.getQuizId())
                .orElseThrow(() -> new RuntimeException("Quiz not found"));
    }

    public DailyQuizResult getTodayQuizForUser(Integer userId) {
        LocalDate today = LocalDate.now();
        
        DailyQuizSchedule schedule = dailyQuizScheduleRepository.findByQuizDate(today)
                .orElseThrow(() -> new RuntimeException("No daily quiz scheduled for today"));
        
        Quiz quiz = quizRepository.findById(schedule.getQuizId())
                .orElseThrow(() -> new RuntimeException("Quiz not found"));

        Optional<DailySolved> solved = dailySolvedRepository.findByUserIdAndQuizIdAndSolvedAt(
                userId, quiz.getQuizId(), today);

        if (solved.isPresent()) {
            DailySolved dailySolved = solved.get();
            return DailyQuizResult.builder()
                    .alreadySolved(true)
                    .quiz(null)
                    .solvedAt(dailySolved.getSolvedAt().toString())
                    .score(dailySolved.getScore())
                    .ranking(dailySolved.getRanking())
                    .build();
        } else {
            return DailyQuizResult.builder()
                    .alreadySolved(false)
                    .quiz(quiz)
                    .solvedAt(null)
                    .score(null)
                    .ranking(null)
                    .build();
        }
    }

    @Transactional
    public SolveResult solveDailyQuiz(Integer userId, Integer quizId, Boolean userAnswer, Integer solvedTimeSeconds) {
        LocalDate today = LocalDate.now();
        
        if (dailySolvedRepository.existsByUserIdAndQuizIdAndSolvedAt(userId, quizId, today)) {
            throw new RuntimeException("Already solved today's quiz");
        }

        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new RuntimeException("Quiz not found"));

        boolean isCorrect = quiz.getAnswer().equals(userAnswer);
        int score = isCorrect ? 100 : 0;

        int ranking = calculateRanking(quizId, today, solvedTimeSeconds);
        int bonusAmount = calculateBonusAmount(ranking);
        int streak = calculateStreak(userId, today);

        DailySolved dailySolved = DailySolved.builder()
                .solvedId(generateSolvedId())
                .userId(userId.longValue())
                .quizId(quizId)
                .score(score)
                .solvedAt(today)
                .solvedTimeSeconds(solvedTimeSeconds)
                .ranking(ranking)
                .bonusAmount(bonusAmount)
                .streak(streak)
                .build();

        dailySolvedRepository.save(dailySolved);

        updateKnowTerm(userId, quiz.getTermId(), isCorrect);
        
        // 보너스 투자금을 계좌에 추가
        if (bonusAmount > 0) {
            accountService.addQuizBonus(userId.longValue(), bonusAmount);
            log.info("Added quiz bonus {} to user {}", bonusAmount, userId);
        }

        return SolveResult.builder()
                .correct(isCorrect)
                .score(score)
                .ranking(ranking)
                .bonusAmount(bonusAmount)
                .streak(streak)
                .explanation(quiz.getExplanation())
                .build();
    }

    private int calculateRanking(Integer quizId, LocalDate today, Integer solvedTimeSeconds) {
        Long fasterSolvers = dailySolvedRepository.countFasterSolvers(quizId, today, solvedTimeSeconds);
        return fasterSolvers.intValue() + 1;
    }

    private int calculateBonusAmount(int ranking) {
        return switch (ranking) {
            case 1 -> 100000;
            case 2 -> 50000;
            case 3 -> 30000;
            default -> {
                if (ranking <= 10) yield 10000;
                else yield 2000;
            }
        };
    }

    private int calculateStreak(Integer userId, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        
        // 어제 풀이 기록 확인
        Optional<DailySolved> yesterdaySolved = dailySolvedRepository.findByUserIdAndSolvedAtBetween(
                userId, yesterday, yesterday).stream().findFirst();
        
        if (yesterdaySolved.isPresent()) {
            // 어제 풀었으면 연속 스트릭 +1
            return yesterdaySolved.get().getStreak() != null ? yesterdaySolved.get().getStreak() + 1 : 1;
        } else {
            // 어제 안 풀었으면 새로운 스트릭 시작
            return 1;
        }
    }

    public int getCurrentStreak(Integer userId) {
        Optional<DailySolved> latest = dailySolvedRepository.findLatestByUserId(userId);
        if (latest.isPresent()) {
            DailySolved latestSolved = latest.get();
            LocalDate today = LocalDate.now();
            
            // 오늘 또는 어제까지 연속으로 풀었는지 확인
            if (latestSolved.getSolvedAt().equals(today) || latestSolved.getSolvedAt().equals(today.minusDays(1))) {
                return latestSolved.getStreak() != null ? latestSolved.getStreak() : 0;
            }
        }
        return 0;
    }

    private void updateKnowTerm(Integer userId, Integer termId, boolean correct) {
        Optional<KnowTerm> existingKnowTerm = knowTermRepository.findByUserIdAndTermId(userId, termId);
        
        if (existingKnowTerm.isPresent()) {
            KnowTerm knowTerm = existingKnowTerm.get();
            knowTerm.setCorrect(correct);
            knowTermRepository.save(knowTerm);
            log.info("Updated know_term for user {} term {} correct {}", userId, termId, correct);
        } else {
            KnowTerm newKnowTerm = KnowTerm.builder()
                    .knowId(generateKnowId())
                    .userId(userId.longValue())
                    .termId(termId)
                    .correct(correct)
                    .build();
            knowTermRepository.save(newKnowTerm);
            log.info("Created new know_term for user {} term {} correct {}", userId, termId, correct);
        }
    }

    private Integer generateKnowId() {
        return (int) System.currentTimeMillis();
    }

    private Integer generateSolvedId() {
        return (int) System.currentTimeMillis();
    }

    public static class DailyQuizResult {
        public boolean alreadySolved;
        public Quiz quiz;
        public String solvedAt;
        public Integer score;
        public Integer ranking;

        public static DailyQuizResultBuilder builder() {
            return new DailyQuizResultBuilder();
        }

        public static class DailyQuizResultBuilder {
            private boolean alreadySolved;
            private Quiz quiz;
            private String solvedAt;
            private Integer score;
            private Integer ranking;

            public DailyQuizResultBuilder alreadySolved(boolean alreadySolved) {
                this.alreadySolved = alreadySolved;
                return this;
            }

            public DailyQuizResultBuilder quiz(Quiz quiz) {
                this.quiz = quiz;
                return this;
            }

            public DailyQuizResultBuilder solvedAt(String solvedAt) {
                this.solvedAt = solvedAt;
                return this;
            }

            public DailyQuizResultBuilder score(Integer score) {
                this.score = score;
                return this;
            }

            public DailyQuizResultBuilder ranking(Integer ranking) {
                this.ranking = ranking;
                return this;
            }

            public DailyQuizResult build() {
                DailyQuizResult result = new DailyQuizResult();
                result.alreadySolved = this.alreadySolved;
                result.quiz = this.quiz;
                result.solvedAt = this.solvedAt;
                result.score = this.score;
                result.ranking = this.ranking;
                return result;
            }
        }
    }

    public static class SolveResult {
        public boolean correct;
        public int score;
        public int ranking;
        public int bonusAmount;
        public int streak;
        public String explanation;

        public static SolveResultBuilder builder() {
            return new SolveResultBuilder();
        }

        public static class SolveResultBuilder {
            private boolean correct;
            private int score;
            private int ranking;
            private int bonusAmount;
            private int streak;
            private String explanation;

            public SolveResultBuilder correct(boolean correct) {
                this.correct = correct;
                return this;
            }

            public SolveResultBuilder score(int score) {
                this.score = score;
                return this;
            }

            public SolveResultBuilder ranking(int ranking) {
                this.ranking = ranking;
                return this;
            }

            public SolveResultBuilder bonusAmount(int bonusAmount) {
                this.bonusAmount = bonusAmount;
                return this;
            }

            public SolveResultBuilder streak(int streak) {
                this.streak = streak;
                return this;
            }

            public SolveResultBuilder explanation(String explanation) {
                this.explanation = explanation;
                return this;
            }

            public SolveResult build() {
                SolveResult result = new SolveResult();
                result.correct = this.correct;
                result.score = this.score;
                result.ranking = this.ranking;
                result.bonusAmount = this.bonusAmount;
                result.streak = this.streak;
                result.explanation = this.explanation;
                return result;
            }
        }
    }
}
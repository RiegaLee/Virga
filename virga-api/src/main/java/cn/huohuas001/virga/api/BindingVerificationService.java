package cn.huohuas001.virga.api;

/** Host-owned, constrained workflow for one-time Minecraft binding verification. */
public interface BindingVerificationService {
    BindingVerificationService UNAVAILABLE = new BindingVerificationService() {
        @Override
        public BindingChallengeResult createChallenge(BindingChallengeRequest request) {
            return BindingChallengeResult.of(BindingChallengeResult.Status.UNAVAILABLE);
        }

        @Override
        public BindingVerificationResult confirmChallenge(BindingConfirmation confirmation) {
            return BindingVerificationResult.rejected(BindingVerificationResult.Status.UNAVAILABLE, 0);
        }
    };

    BindingChallengeResult createChallenge(BindingChallengeRequest request);

    BindingVerificationResult confirmChallenge(BindingConfirmation confirmation);
}

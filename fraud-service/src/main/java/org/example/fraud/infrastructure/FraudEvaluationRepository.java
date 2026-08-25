package org.example.fraud.infrastructure;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FraudEvaluationRepository extends JpaRepository<FraudEvaluationEntity, UUID> {

  @Query(
      value =
          """
    select f.*
    from fraud_evaluation f
    where f.account_id = :accountId
      and f.idempotency_key = :idempotencyKey
    limit 1
    """,
      nativeQuery = true)
  Optional<FraudEvaluationEntity> findByAccountIdAndIdempotencyKey(
      @Param("accountId") UUID accountId, @Param("idempotencyKey") String idempotencyKey);

  @Query(
      value =
          """
    select avg(f.amount) as avgAmount,
       count(*) as txnCount
    from fraud_evaluation f
    where f.account_id = :accountId
      and f.decision = :decision
      and f.created_at >= now() - (INTERVAL '1 day' * :days)
    """,
      nativeQuery = true)
  AmountBaseline findAmountBaseline(
      @Param("accountId") UUID accountId,
      @Param("decision") String decision,
      @Param("days") int days);

  @Modifying
  @Query(
      value =
          """
      insert into fraud_evaluation (
        evaluation_id, account_id, amount, currency_code, merchant_reference,
        idempotency_key, request_hash, risk_score, decision, rules_version,
        rule_result, ip_address, correlation_id, created_at, lock_recommended, lock_reason_code
      ) values (
        :evaluationId, :accountId, :amount, :currencyCode, :merchantReference,
        :idempotencyKey, :requestHash, 0, :decision, :rulesVersion,
        cast(:ruleResultJson as jsonb), cast(:ipAddress as inet), :correlationId, :createdAt,
        false, null
      )
      on conflict (account_id, idempotency_key) do nothing
      """,
      nativeQuery = true)
  int tryInsertPending(
      @Param("evaluationId") UUID evaluationId,
      @Param("accountId") UUID accountId,
      @Param("amount") BigDecimal amount,
      @Param("currencyCode") String currencyCode,
      @Param("merchantReference") String merchantReference,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("requestHash") String requestHash,
      @Param("decision") String decision, // "PENDING"
      @Param("rulesVersion") String rulesVersion,
      @Param("ruleResultJson") String ruleResultJson, // "[]"
      @Param("ipAddress") String ipAddress,
      @Param("correlationId") UUID correlationId,
      @Param("createdAt") OffsetDateTime createdAt);


  @Modifying
  @Query(
    value = """
      update fraud_evaluation
         set risk_score = :riskScore,
             decision = :decision,
             rule_result = cast(:ruleResultJson as jsonb),
             lock_recommended = :lockRecommended,
             lock_reason_code = :lockReasonCode
       where evaluation_id = :evaluationId
         and decision = 'PENDING'
      """,
    nativeQuery = true)
  int finalizeEvaluation(
    @Param("evaluationId") UUID evaluationId,
    @Param("riskScore") int riskScore,
    @Param("decision") String decision,
    @Param("ruleResultJson") String ruleResultJson,
    @Param("lockRecommended") boolean lockRecommended,
    @Param("lockReasonCode") String lockReasonCode);
}

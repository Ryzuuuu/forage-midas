package com.jpmc.midascore.incentive;

import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.foundation.Incentive;
import com.jpmc.midascore.foundation.Transaction;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Asks the external Incentive API how much bonus the recipient of a valid transaction earns. */
@Component
public class IncentiveClient {
    private static final BigDecimal NONE = BigDecimal.ZERO.setScale(UserRecord.SCALE);

    private final IncentiveProperties properties;
    private final RestClient restClient;

    public IncentiveClient(IncentiveProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder.requestFactory(requestFactory).build();
    }

    /**
     * @return the incentive, never negative
     * @throws IncentiveUnavailableException when the API cannot be reached or answers badly, so the
     *         Kafka error handler can retry the message instead of silently dropping the bonus
     */
    public BigDecimal fetchIncentive(Transaction transaction) {
        if (!properties.enabled()) {
            return NONE;
        }
        Incentive incentive;
        try {
            incentive = restClient.post()
                    .uri(properties.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(transaction)
                    .retrieve()
                    .body(Incentive.class);
        } catch (RestClientException e) {
            throw new IncentiveUnavailableException("Incentive API call failed for " + transaction, e);
        }
        if (incentive == null || !Float.isFinite(incentive.getAmount())) {
            throw new IncentiveUnavailableException("Incentive API returned no usable amount for " + transaction, null);
        }
        BigDecimal amount = new BigDecimal(Float.toString(incentive.getAmount())).setScale(UserRecord.SCALE, RoundingMode.HALF_EVEN);
        return amount.signum() < 0 ? NONE : amount;
    }
}

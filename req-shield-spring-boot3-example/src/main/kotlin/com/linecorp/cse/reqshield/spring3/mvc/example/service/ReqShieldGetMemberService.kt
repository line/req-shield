package com.linecorp.cse.reqshield.spring3.mvc.example.service

import com.linecorp.cse.reqshield.ReqShield
import com.linecorp.cse.reqshield.spring3.mvc.example.dto.Member
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

private val log = LoggerFactory.getLogger(ReqShieldGetMemberService::class.java)

@Service
class ReqShieldGetMemberService(
    val reqShield: ReqShield<Member>,
) {
    fun getMemberById(memberId: String): Member? {
        return reqShield.getAndSetReqShieldData(
            name = "member",
            key = memberId,
            callable = {
                Thread.sleep(500)
                log.info("get product with 0.5s delay (Simulate db request) / memberId : $memberId")
                Member(id = memberId, "member_$memberId")
            },
            timeToLiveMillis = Duration.ofMinutes(5).toMillis(),
        ).value
    }
}

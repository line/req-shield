package com.linecorp.cse.reqshield.spring3.mvc.example.service

import com.linecorp.cse.reqshield.ReqShield
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring3.mvc.example.dto.Member
import com.linecorp.cse.reqshield.spring3.mvc.example.dto.Product
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.Objects
import java.util.UUID

private val log = LoggerFactory.getLogger(ReqShieldGetMemberService::class.java)

@Service
class ReqShieldGetMemberService(
    val reqShield: ReqShield<Member>,
) {
    fun getMemberById(memberId: String): Member? {
        return reqShield.getAndSetReqShieldData(
            name = "memberCache",  // New parameter
            key = "member:$memberId",
            callable = {

            },
            timeToLiveMillis = Duration.ofMinutes(5).toMillis()
        ).value
    }
}

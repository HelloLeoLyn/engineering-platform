setpackage com.engineeringplatform.sample.customer.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface SampleCustomerMapper extends BaseMapper<SampleCustomerRecord> { }

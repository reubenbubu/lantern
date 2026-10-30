package com.example.shop.catalog;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** A MyBatis mapper: statements in annotations, and more in mapper/ProductMapper.xml. */
@Mapper
public interface ProductMapper {

  @Select("SELECT id, name FROM product WHERE id = #{id}")
  Object findById(long id);

  @Insert({"INSERT INTO product (id, name)", "VALUES (#{id}, #{name})"})
  int insert(Object product);

  /** Text substitution: the table may be made at run time, so nothing is read. */
  @Update("UPDATE ${table} SET name = #{name}")
  int rename(String table, String name);

  List<Object> findByCategory(String category);

  int archive(long id, String reason);

  int purge(List<Long> ids);

  int snapshot();
}

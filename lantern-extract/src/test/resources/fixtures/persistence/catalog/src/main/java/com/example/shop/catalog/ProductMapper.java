package com.example.shop.catalog;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
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

  @Delete("DELETE FROM product_tag WHERE product_id = #{id}")
  int untag(long id);

  List<Object> findByCategory(String category);

  /** An overload: the XML statement runs for both. */
  List<Object> findByCategory(String category, int limit);

  List<Object> search(String name);

  int retitle(long id, String title);

  int archive(long id, String reason);

  int purge(List<Long> ids);

  int snapshot();
}

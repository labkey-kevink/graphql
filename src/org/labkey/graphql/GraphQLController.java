/*
 * Copyright (c) 2015 LabKey Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.labkey.graphql;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeReference;
import org.labkey.api.action.ApiAction;
import org.labkey.api.action.Marshal;
import org.labkey.api.action.Marshaller;
import org.labkey.api.action.SimpleViewAction;
import org.labkey.api.action.SpringActionController;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.AbstractTableInfo;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.Container;
import org.labkey.api.data.ForeignKey;
import org.labkey.api.data.JdbcType;
import org.labkey.api.data.MultiValuedForeignKey;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.RowIdForeignKey;
import org.labkey.api.query.UserIdForeignKey;
import org.labkey.api.query.UserSchema;
import org.labkey.api.security.RequiresPermission;
import org.labkey.api.security.User;
import org.labkey.api.security.permissions.ReadPermission;
import org.labkey.api.util.StringExpression;
import org.labkey.api.view.JspView;
import org.labkey.api.view.NavTree;
import org.labkey.api.view.NotFoundException;
import org.springframework.validation.BindException;
import org.springframework.web.servlet.ModelAndView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLFloat;
import static graphql.Scalars.GraphQLID;
import static graphql.Scalars.GraphQLInt;
import static graphql.Scalars.GraphQLLong;
import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLInterfaceType.newInterface;
import static graphql.schema.GraphQLObjectType.newObject;

@Marshal(Marshaller.Jackson)
public class GraphQLController extends SpringActionController
{
    private static final DefaultActionResolver _actionResolver = new DefaultActionResolver(GraphQLController.class);
    public static final String NAME = "graphql";

    public GraphQLController()
    {
        setActionResolver(_actionResolver);
    }

    @RequiresPermission(ReadPermission.class)
    public class BeginAction extends SimpleViewAction
    {
        public ModelAndView getView(Object o, BindException errors) throws Exception
        {
            return new JspView("/org/labkey/graphql/view/hello.jsp");
        }

        public NavTree appendNavTrail(NavTree root)
        {
            return root;
        }
    }

    @RequiresPermission(ReadPermission.class)
    public class HelloAction extends ApiAction<Object>
    {
        @Override
        public Object execute(Object o, BindException errors) throws Exception
        {
            GraphQLObjectType queryType = newObject()
                    .name("helloWorldQuery")
                    .field(newFieldDefinition()
                            .type(GraphQLString)
                            .name("hello")
                            .staticValue("world")
                            .build())
                    .build();

            GraphQLSchema schema = GraphQLSchema.newSchema()
                    .query(queryType)
                    .build();
            ExecutionResult result = new GraphQL(schema).execute("{hello}");
            Object data = result.getData();
            return success(data);
        }
    }

    public static class QForm
    {
        private String _schemaName;
        private String _queryName;

        private String _q;
        private Map<String, Object> _variables;

        public String getSchemaName()
        {
            return _schemaName;
        }

        public void setSchemaName(String schemaName)
        {
            _schemaName = schemaName;
        }

        public String getQueryName()
        {
            return _queryName;
        }

        public void setQueryName(String queryName)
        {
            _queryName = queryName;
        }

        public String getQ()
        {
            return _q;
        }

        public void setQ(String q)
        {
            _q = q;
        }
    }

    /*
     * Notes about GraphQL:
     *
     * - Case-sensitive names
     *
     * - No overloading of queries based on arguments -- however, optional arguments are ok.
     *
     * - Nothing like "*" to get all fields.  Fields must be explicitly requested:
     * {
     *     user(name:"bob") {
     *         id, name, friends { id, name }
     *     }
     * }
     *
     * - doesn't seem to allow lazily creation of object types as needed.  For example, consider a column with FK to core.users:
     *   in query we lazily create the core.users table and resolve lookup columns if the user adds the "CreatedBy/Email" column from the parent table.
     *   In GraphQL, the parser expects all fields and types be constructed when parsing the query.
     *
     * - fields can themselves have arguments
     *
     * - argument can be an object so we could pass in filter, sort, (offset, maxRows?)
     * {
     *      user(name: "bob") {
     *          id, name,
     *          friends({
     *              filters: [ "name", "startswith", "s" ],
     *              sort: "createdby",
     *          })
     *      }
     * }
     *
     * - pagination pattern: http://graphql.org/learn/pagination/
     *
     * - Recommendation is to use static query strings placed in .graphql files.
     *   https://dev-blog.apollodata.com/5-benefits-of-static-graphql-queries-b7fa90b0b69a#.r4fapgew8
     *   Facebook allows all queries in dev mode, but saves queries to db and only allows those queries in production mode
     *   In addition, it can refer to the query by id so save transmitting query string.
     *
     * - Consider building the query type-system once -- not dynamically per-request
     *
     * - May want to try using some form of derivation.  e.g., exp.Data table could be a base type and each DataClass could be a derived type.
     *
     * - Coalesce/batch expensive queries -- eg., if there is a "Outputs" query field on a DataClass type and the
     *   outer query selects more than one DataClass row, we could issue a single lineage query to get the
     *   outputs for both rows at one time.
     *
     *
     * - Use a client-side cache similar to Relay.
     *   https://www.npmjs.com/package/cashay
     *
     * - Don't use TableSelector.ALL_COLUMNS ? -- only select columns that were asked for
     *
     * ======
     *
     * Maybe we can get most of what GraphQL provides if we supported a "sub-field" syntax for columns
     * had a better multi-value FK response format:
     *
     * schemaName: "exp.data",
     * queryName: "Molecule",
     * columns: [ "Name", "Components {RowId, Name, Parents { Name } }" ]
     *
     * ==> response:
     *
     * [{
     *      Name: "M-1",
     *      Components: [{
     *          RowId: 123, Name: "PS-1", Parents: [{
     *              Name: "Parent-1"
     *          }]
     *      },{
     *          RowId: 124, Name: "PS-2", Parents: []
     *      }]
     * },{
     *      Name: "M-2", ...
     * ]}
     *
     * - Even better would be to pull out references to the top-level
     *
     */

    /**
     *
     * Example queries:
     *
     *  LABKEY.Ajax.request({
     *      url: LABKEY.ActionURL.buildURL("graphql", "query.api"),
     *      jsonData: {
     *          schemaName: "exp.data",
     *          queryName: "CellLine",
     *          q: "{ CellLine(RowId:15920) {
     *                  links { rel, href },
     *                  Name,
     *                  ExpressionSystemId { links { rel, href }, Name, RowId, LSID },
     *                  Container { Name, EntityId },
     *                  clonal
     *              } }"
     *      }
     *  });
     *
     * LABKEY.Ajax.request({
     *  url: LABKEY.ActionURL.buildURL("graphql", "query.api"),
     *  jsonData: {
     *    schemaName: "lists", queryName: "mylist",
     *    q: "{ __schema { types { name } } }"
     *  }
     *});
     *
     *
     */
    @RequiresPermission(ReadPermission.class)
    public class QueryAction extends ApiAction<QForm>
    {
        @Override
        public Object execute(QForm form, BindException errors) throws Exception
        {
            UserSchema schema = QueryService.get().getUserSchema(getUser(), getContainer(), form.getSchemaName());
            if (schema == null)
                throw new NotFoundException("schema: " + form.getSchemaName());

            TableInfo table = schema.getTable(form.getQueryName());
            if (table == null)
                throw new NotFoundException("query: " + form.getQueryName());

            GraphQLSchema gqlSchema = createSchema(table);
            ExecutionResult result = new GraphQL(gqlSchema).execute(form.getQ());
            if (!result.getErrors().isEmpty())
            {
                errors.reject(ERROR_MSG, result.getErrors().get(0).getMessage());
                return null;
            }

            Object ret = result.getData();
            //return success(ret);
            return ret;
        }
    }

    public static GraphQLSchema createSchema(TableInfo table) //UserSchema schema)
    {
        // create types for use in type references
        Set<GraphQLType> types = new HashSet<>();

        // CONSIDER: Add types for "Nameable" and "ExpObject", "ExpMaterial", ...

        // Add "core__Users" table type to avoid issue with UserIdForeignKey exposing schema tables instead of query tables
        final User user = table.getUserSchema().getUser();
        final Container container = table.getUserSchema().getContainer();
        final UserSchema coreSchema = QueryService.get().getUserSchema(user, container, "core");
        final TableInfo usersTable = coreSchema.getTable("Users");
        types.add(createObject(usersTable, "core__Users", types));

        // Unlike the UserIdForeignKey, ContainerForeignKey works correctly with query tables
//        types.add(newObject()
//                .name("core__Containers")
//                .field(newFieldDefinition()
//                        .name("id")
//                        .type(new GraphQLNonNull(GraphQLID))
//                        .build()
//                )
//                .field(newFieldDefinition()
//                        .name("name")
//                        .type(new GraphQLNonNull(GraphQLString))
//                        .build()
//                )
//                .build()
//        );


        types.add(newObject()
                .name("link")
                .field(newFieldDefinition()
                        .name("rel")
                        .type(GraphQLString)
                        .build()
                )
                .field(newFieldDefinition()
                        .name("href")
                        .type(GraphQLString)
                        .build()
                )
                .build()
        );


        types.add(newInterface()
                .name("HasLinks")
                .description("A collection of links")
                .field(createLinksField(null))
                .typeResolver(object -> {
                    // i dunno -- just assume the row has links?
                    if (object != null)
                        return GraphQLObjectType.reference("HasLinks"); // TODO: I think we need a concrete type here, not an interface

                    return null;
                })
                .build()
        );

        return GraphQLSchema
                .newSchema()
                .query(createQueryObject(table, types))
                .build(types);
    }

    public static GraphQLObjectType createQueryObject(TableInfo table, Set<GraphQLType> types)
    {
        return newObject()
                .name("Query")

                .field(newFieldDefinition()
                        .name(table.getName())
                        .type(createObject(table, null, types))
                        .argument(table.getPkColumns().stream()
                                .map(pkCol -> newArgument()
                                        .name(pkCol.getName())
                                        .description(pkCol.getDescription())
                                        .type(intype(pkCol, types))
                                        // mark as optional so we can create an overloaded 'filters' arg
                                        .build()
                                )
                                .collect(Collectors.toList())
                        )

                        // TODO: Allow traditional LabKey query filters
//                        .argument(createQueryFilterArgument())

                        // TODO: Consider adding altKeys as parameters
//                        .argument(table.getAlternateKeyColumns().stream()
//                                .map(altCol -> newArgument()
//                                        .name(altCol.getName())
//                                        .description(altCol.getDescription())
//                                        .type(intype(altCol))
//                                        .build()
//                                )
//                                .collect(Collectors.toList())
//                        )
                        .dataFetcher(env -> {
                            SimpleFilter filter = new SimpleFilter();
                            for (String name : table.getPkColumnNames())
                            {
                                Object val = env.getArgument(name);
                                filter.addCondition(name, val);
                            }

                            Map<String, Object> ret = new TableSelector(table, TableSelector.ALL_COLUMNS, filter, null).getMap();
                            return ret;
                        })
                        .build()
                )
                .build()
                ;
    }

//    public static GraphQLArgument createQueryFilterArgument()
//    {
//
//    }

    public static GraphQLObjectType createObject(TableInfo table, String nameOverride, Set<GraphQLType> types)
    {
        return newObject()
                .name(nameOverride == null ? table.getName() : nameOverride)
                .withInterface(GraphQLInterfaceType.reference("HasLinks"))
                .description(table.getDescription())
                .fields(createFields(table.getColumns(), types))
                .field(createLinksField(table))
                .build();
    }

    public static GraphQLFieldDefinition createLinksField(TableInfo table)
    {
        return newFieldDefinition()
                .name("links")
                .type(new GraphQLList(new GraphQLNonNull(new GraphQLTypeReference("link"))))
                .dataFetcher(environment -> {
                    Object source = environment.getSource();
                    if (!(source instanceof Map))
                        return null;

                    Map<String, Object> row = (Map<String, Object>)source;

                    ArrayList<Map<String, Object>> links = new ArrayList<>();

                    // detailsURL
                    StringExpression detailsUrl = table.getDetailsURL(null, null);
                    if (detailsUrl != null && detailsUrl != AbstractTableInfo.LINK_DISABLER)
                    {
                        String href = detailsUrl.eval(row);
                        links.add(CaseInsensitiveHashMap.of("rel", "details", "href", href));
                    }

                    // TODO: Doesn't render default update links if table is insertable
                    // updateURL
                    StringExpression updateUrl = table.getUpdateURL(null, null);
                    if (updateUrl != null && updateUrl != AbstractTableInfo.LINK_DISABLER)
                    {
                        String href = updateUrl.eval(row);
                        links.add(CaseInsensitiveHashMap.of("rel", "update", "href", href));
                    }

                    return links;
                })
                .build();
    }

    public static List<GraphQLFieldDefinition> createFields(List<ColumnInfo> columns, Set<GraphQLType> types)
    {
        return columns.stream()
                .map(col -> GraphQLController.createField(col, types))
                .collect(Collectors.toList());
    }

    public static GraphQLFieldDefinition createField(ColumnInfo column, Set<GraphQLType> types)
    {
        return newFieldDefinition()
                .name(column.getName())
                .description(column.getDescription())
                .type(type(column, types))
                .dataFetcher(dataFetcher(column))
                .build();
    }

    public static GraphQLOutputType type(ColumnInfo column, Set<GraphQLType> types)
    {
        // TODO: Use GraphQLTypeReference for self-references

        GraphQLOutputType type;

        JdbcType jdbcType = column.getJdbcType();
        switch (jdbcType)
        {
            case BOOLEAN:
                type = GraphQLBoolean;
                break;

            case BIGINT:
            case DECIMAL:
                type = GraphQLLong;
                break;

            case DOUBLE:
            case REAL:
                type = GraphQLFloat;
                break;

            case SMALLINT:
            case INTEGER:
            case TINYINT:
                type = GraphQLInt;
                break;

            case DATE:
            case TIME:
            case TIMESTAMP:
                type = GraphQLString; // TODO: dates
                break;

            case CHAR:
            case VARCHAR:
            case LONGVARCHAR:
                type = GraphQLString;
                break;

            case GUID:
                type = GraphQLID;
                break;

            case BINARY:
            case VARBINARY:
            case LONGVARBINARY:
            case NULL:
            case OTHER:
            default:
                //throw new UnsupportedOperationException("Type '" + jdbcType + "' not supported on column '" + column.getParentTable().getName() + "." + column.getName() + "'");
                type = GraphQLString;
        }

        if (type == null)
            type = GraphQLString;

        boolean required = column.isRequired();
        if (required)
            type = new GraphQLNonNull(type);

        ForeignKey fk = column.getFk();
        if (fk != null)
        {
            if (fk instanceof RowIdForeignKey)
            {
                // do nothing -- keep the original type
            }
            // NOTE: Just use query metadata instead of a graphql type ?
//            else if (fk instanceof ContainerForeignKey)
//            {
//                type = new GraphQLTypeReference("core__Containers");
//            }
            else if (fk instanceof UserIdForeignKey)
            {
                type = new GraphQLTypeReference("core__Users");
            }
            else
            {
                GraphQLType fkType = ensureType(fk, types);
                if (fkType != null)
                    type = new GraphQLTypeReference(fkType.getName());
            }
        }

        boolean multiValued = fk != null && fk instanceof MultiValuedForeignKey;
        if (multiValued)
            type = new GraphQLList(type);

        return type;
    }

    public static GraphQLInputType intype(ColumnInfo column, Set<GraphQLType> types)
    {
        assert column.isKeyField();
        GraphQLOutputType outtype = type(column, types);
        return (GraphQLInputType)outtype;
    }

    // Create the GraphQLType for the foreign key if it hasn't yet been added to the types collection
    public static GraphQLType ensureType(ForeignKey fk, Set<GraphQLType> types)
    {
        // Only create GraphQLType for lookups in the public schema
        if (fk.getLookupSchemaName() == null || fk.getLookupTableName() == null)
            return null;

        String typeName = fk.getLookupSchemaName().replace(".", "_") + "__" + fk.getLookupTableName();

        GraphQLType fkType = findType(typeName, types);
        if (fkType == null)
        {
            TableInfo lookupTable = fk.getLookupTableInfo();
            if (lookupTable == null)
                return null;

            // Add a temporary type reference avoid type recursion going to infinity
            GraphQLType temp = new GraphQLTypeReference(typeName);
            types.add(temp);
            fkType = createObject(fk.getLookupTableInfo(), typeName, types);
            types.add(fkType);
            types.remove(temp);
        }

        return fkType;
    }

    public static GraphQLType findType(String typeName, Set<GraphQLType> types)
    {
        for (GraphQLType type : types)
        {
            if (typeName.equals(type.getName()))
                return type;
        }

        return null;
    }

    public static DataFetcher dataFetcher(ColumnInfo column)
    {
        final ForeignKey fk = column.getFk();
        if (fk == null || fk instanceof RowIdForeignKey)
            return null; // use the default PropertyDataFetcher

        //TODO: column.getDefaultValue()

        return new DataFetcher()
        {
            @Override
            public Object get(DataFetchingEnvironment env)
            {
                Map<String, Object> row = (Map<String, Object>)env.getSource();
                Object value = row.get(column.getName());
                if (value == null)
                    return null;

                //Container lookupContainer = fk.getLookupContainer()
                if (fk instanceof MultiValuedForeignKey)
                {
//                    MultiValuedForeignKey mvfk = (MultiValuedForeignKey)fk;
//                    String junctionLookupColumn = mvfk.getJunctionLookup(); // column on junctionTable that has an FK to the value table
//                    String junctionTableName = mvfk.getLookupTableName();
//                    String junctionKey = mvfk.getLookupColumnName();
//
//                    ColumnInfo lookupColumn = fk.createLookupColumn(column, null);
//                    //TableInfo junctionTable = lookupColumn.getParentTable(); // not right
//
//                    TableInfo valueTable = mvfk.getLookupTableInfo(); // far right table
//
//                    SimpleFilter filter = new SimpleFilter();
//                    filter.addWhereClause(
//                            lookupColumn.getSelectName() + " IN (SELECT " + junctionLookupColumn + " FROM " + junctionTable.getFromSQL("q") + " WHERE " + junctionKey + " = ?", new Object[] { value }, null);
//                    TableSelector ts = new TableSelector(valueTable, filter, null);
//                    return ts.getMapCollection();
                    return Arrays.asList(
                            CaseInsensitiveHashMap.of("name", "bob"),
                            CaseInsensitiveHashMap.of("name", "sally")
                    );
                }
                else
                {
                    TableInfo lookupTable = fk.getLookupTableInfo();
                    ColumnInfo lookupColumn = lookupTable.getColumn(fk.getLookupColumnName());

                    TableSelector ts = new TableSelector(lookupTable, new SimpleFilter(lookupColumn.getName(), value), null);
                    return ts.getMap();
                }
            }
        };
    }
}

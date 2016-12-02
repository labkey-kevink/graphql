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
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.ContainerForeignKey;
import org.labkey.api.data.ForeignKey;
import org.labkey.api.data.JdbcType;
import org.labkey.api.data.MultiValuedForeignKey;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.query.QueryDefinition;
import org.labkey.api.query.QueryException;
import org.labkey.api.security.RequiresPermission;
import org.labkey.api.security.permissions.ReadPermission;
import org.labkey.api.view.JspView;
import org.labkey.api.view.NavTree;
import org.labkey.api.view.NotFoundException;
import org.springframework.validation.BindException;
import org.springframework.web.servlet.ModelAndView;

import java.util.ArrayList;
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

    public static class QForm extends org.labkey.api.query.QueryForm
    {
        private String _q;
        private Map<String, Object> _variables;

        public String getQ()
        {
            return _q;
        }

        public void setQ(String q)
        {
            _q = q;
        }
    }

    // Example queries:
    //
    // LABKEY.Ajax.request({
    //  url: LABKEY.ActionURL.buildURL("graphql", "query.api"),
    //  jsonData: {
    //    schemaName: "lists", queryName: "mylist",
    //    q: "{ __schema { types { name } } }"
    //  }
    //});
    //
    @Marshal(Marshaller.JSONObject) // need to use JSONObject marshalling due to QueryForm binding being fucked
    @RequiresPermission(ReadPermission.class)
    public class QueryAction extends ApiAction<QForm>
    {
        @Override
        public Object execute(QForm form, BindException errors) throws Exception
        {
            QueryDefinition qdef = form.getQueryDef();
            if (qdef == null)
                throw new NotFoundException();

            List<QueryException> queryErrors = new ArrayList<>();
            TableInfo table = qdef.getTable(queryErrors, true);
            if (!queryErrors.isEmpty())
            {
                errors.reject(ERROR_MSG, queryErrors.get(0).getMessage());
                return null;
            }

            GraphQLSchema schema = createSchema(table);
            //Object ret = new GraphQL(schema).execute("{ " + table.getName() + " }").getData();
            ExecutionResult result = new GraphQL(schema).execute(form.getQ());
            if (!result.getErrors().isEmpty())
            {
                errors.reject(ERROR_MSG, result.getErrors().get(0).getMessage());
                return null;
                //return new SimpleResponse(false, result.getErrors().get(0).getMessage(), result.getErrors());
            }

            Object ret = result.getData();
            //return success(ret);
            return ret;
        }
    }

    public static GraphQLSchema createSchema(TableInfo table) //UserSchema schema)
    {
        // create types for use in type references
        Set<GraphQLType> dict = new HashSet<>();
        dict.add(newObject()
                .name("container")
                .field(newFieldDefinition()
                        .name("id")
                        .type(GraphQLID)
                    .build())
            .build());

        return GraphQLSchema
                .newSchema()
                .query(createQueryObject(table))
                .build(dict);
    }

    public static GraphQLObjectType createQueryObject(TableInfo table)
    {
        ColumnInfo pkCol = table.getPkColumns().get(0);

        return newObject()
                .name("Query")
                //.field(createObject(table))
                .field(newFieldDefinition()
                        .name(table.getName())
                        .type(createObject(table)) // could use an interface here ?
                        .argument(newArgument()
                                .name(pkCol.getName())
                                .description(pkCol.getDescription())
                                .type(intype(pkCol))
                                .build()
                        )
                        .dataFetcher(env -> {
                            Object pk = env.getArgument(pkCol.getName());
                            return new TableSelector(table).getMap(pk);
                        })
                        .build()
                )
                .build()
                ;
    }

    public static GraphQLObjectType createObject(TableInfo table)
    {
        return newObject()
                .name(table.getName())
                .description(table.getDescription())
                .fields(createFields(table.getColumns()))
                .build();
    }

    public static List<GraphQLFieldDefinition> createFields(List<ColumnInfo> columns)
    {
        return columns.stream()
                .map(GraphQLController::createField)
                .collect(Collectors.toList());
    }

    public static GraphQLFieldDefinition createField(ColumnInfo column)
    {
        return newFieldDefinition()
                .name(column.getName())
                .description(column.getDescription())
                .type(type(column))
                .dataFetcher(dataFetcher(column))
                .build();
    }

    public static GraphQLOutputType type(ColumnInfo column)
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
                throw new UnsupportedOperationException("NYI");
        }

        if (type == null)
            type = GraphQLString;

        boolean required = column.isRequired();
        if (required)
            type = new GraphQLNonNull(type);

        ForeignKey fk = column.getFk();
        if (fk != null && fk instanceof ContainerForeignKey)
        {
            type = new GraphQLTypeReference("container");
        }

        boolean multiValued = fk != null && fk instanceof MultiValuedForeignKey;
        if (multiValued)
            type = new GraphQLList(type);

        return type;
    }

    public static GraphQLInputType intype(ColumnInfo column)
    {
        assert column.isKeyField();
        GraphQLOutputType outtype = type(column);
        return (GraphQLInputType)outtype;
    }

    public static DataFetcher dataFetcher(ColumnInfo column)
    {
        final ForeignKey fk = column.getFk();
        if (fk == null)
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
                TableInfo lookupTable = fk.getLookupTableInfo();
                ColumnInfo lookupColumn = lookupTable.getColumn(fk.getLookupColumnName());

                TableSelector ts = new TableSelector(lookupTable, new SimpleFilter(lookupColumn.getName(), value), null);
                return ts.getMap();
            }
        };
    }
}

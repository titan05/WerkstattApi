using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace WerkstattApi.Migrations
{
    /// <inheritdoc />
    public partial class addedMitarbeiter : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "Mitarbeiter",
                columns: table => new
                {
                    Id = table.Column<int>(type: "int", nullable: false)
                        .Annotation("SqlServer:Identity", "1, 1"),
                    Name = table.Column<string>(type: "nvarchar(max)", nullable: false),
                    WerkstattId = table.Column<int>(type: "int", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Mitarbeiter", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Mitarbeiter_Werkstatt_WerkstattId",
                        column: x => x.WerkstattId,
                        principalTable: "Werkstatt",
                        principalColumn: "Id");
                });

            migrationBuilder.CreateIndex(
                name: "IX_Mitarbeiter_WerkstattId",
                table: "Mitarbeiter",
                column: "WerkstattId");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "Mitarbeiter");
        }
    }
}

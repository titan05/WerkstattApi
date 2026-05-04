namespace WerkstattApi.Models
{
    public class Mitarbeiter
    {
        public int Id { get; set; }
        public string Name { get; set; }
        public int? WerkstattId { get; set; }
        public Werkstatt? Werkstatt { get; set; }
    }
}
